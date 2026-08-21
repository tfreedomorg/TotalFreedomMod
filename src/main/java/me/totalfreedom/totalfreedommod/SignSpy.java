package me.totalfreedom.totalfreedommod;

import java.time.Duration;
import java.util.*;

import io.papermc.paper.math.Position;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class SignSpy extends FreedomService
{
    private static final int LINES_PER_SIDE = 4;
    private static final Duration VIEW_LIFETIME = Duration.ofMinutes(10);
    private static final long REVERT_DELAY_TICKS = 20L * 60L;
    private static final int FAKE_SIGN_DEPTH = 4;
    private static final int MAX_LINE_CHARS = 50;
    private static final int SUMMARY_CHARS = 90;
    private static final int MIN_CHANGE_CHARS = 8;

    private Map<UUID, Location> pendingReverts;

    private record SignSnapshot(BlockData blockData, Side side,
                                List<Component> lines, List<Component> oldLines,
                                DyeColor color, boolean glowing,
                                List<Component> otherLines, DyeColor otherColor, boolean otherGlowing)
    {
    }

    private record LineChange(boolean added, String text)
    {
    }

    private static Side opposite(final Side side)
    {
        return side == Side.FRONT ? Side.BACK : Side.FRONT;
    }

    public SignSpy(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        this.pendingReverts = new HashMap<>();
    }

    @Override
    protected void onStop()
    {
        for (final UUID viewerId : List.copyOf(pendingReverts.keySet()))
        {
            revertPending(viewerId);
        }
        this.pendingReverts = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event)
    {
        final Player editor = event.getPlayer();

        final Sign oldState = event.getBlock().getState() instanceof Sign sign ? sign : null;
        final SignSide oldSide = oldState != null ? oldState.getSide(event.getSide()) : null;

        final List<LineChange> changes = new ArrayList<>(LINES_PER_SIDE);
        int nonEmptyLines = 0;
        int deletedLines = 0;
        for (int i = 0; i < LINES_PER_SIDE; i++)
        {
            final Component line = event.line(i);
            final String plain = line != null ? AdventureUtil.componentToPlainText(line).trim() : "";
            final String oldPlain = oldSide != null
                    ? AdventureUtil.componentToPlainText(oldSide.line(i)).trim() : "";
            if (!plain.equals(oldPlain))
            {
                if (plain.isEmpty())
                {
                    deletedLines++;
                    changes.add(new LineChange(false, oldPlain));
                }
                else
                {
                    changes.add(new LineChange(true, plain));
                }
            }
            if (!plain.isEmpty())
            {
                nonEmptyLines++;
            }
        }

        if (changes.isEmpty())
        {
            return;
        }

        int otherNonEmptyLines = 0;
        if (oldState != null)
        {
            final SignSide otherSide = oldState.getSide(opposite(event.getSide()));
            for (int i = 0; i < LINES_PER_SIDE; i++)
            {
                if (!AdventureUtil.componentToPlainText(otherSide.line(i)).trim().isEmpty())
                {
                    otherNonEmptyLines++;
                }
            }
        }

        final boolean bothSides = otherNonEmptyLines > 0
                && !(event.getBlock().getBlockData() instanceof WallSign);
        final String context = bothSides
                ? (event.getSide() == Side.FRONT ? " (front)" : " (back)") : "";

        final boolean editorIsAdmin = plugin.al.isAdmin(editor);

        Component message = Component.empty();
        if (editorIsAdmin)
        {
            final Displayable display = plugin.rm.getDisplay(editor);
            String prefix = AdventureUtil.componentToPlainText(display.getColoredTag()).trim();
            if (prefix.isEmpty())
            {
                final String tag = display.getTag();
                prefix = tag != null ? tag : "";
            }
            if (!prefix.isEmpty())
            {
                message = Component.text(prefix + " ", display.getColor());
            }
        }
        message = message.append(Component.text(
                editor.getName() + " edited sign" + context, NamedTextColor.GRAY));

        final List<LineChange> shown = new ArrayList<>(changes.size());
        int budget = SUMMARY_CHARS;
        for (final LineChange change : changes)
        {
            if (budget < MIN_CHANGE_CHARS && !shown.isEmpty())
            {
                break;
            }
            final String text = change.text();
            final int room = Math.min(budget, MAX_LINE_CHARS);
            shown.add(text.length() > room
                    ? new LineChange(change.added(), String.format("%s[...]", text.substring(0, room))) : change);
            budget -= Math.min(text.length(), room);
        }

        boolean firstChange = true;
        for (final LineChange change : shown)
        {
            message = message.append(Component.text(firstChange ? " " : ", ", NamedTextColor.GRAY))
                    .append(Component.text(change.added() ? "+" : "-",
                            change.added() ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(Component.text("'" + change.text() + "'", NamedTextColor.GRAY));
            firstChange = false;
        }

        if (deletedLines > 0)
        {
            final SignSnapshot snapshot = snapshot(event, oldState);
            message = message.append(Component.text(" [", NamedTextColor.GRAY))
                    .append(viewButton("Before", "Click to view the sign before this edit",
                            snapshot, snapshot.side(), true))
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(viewButton("After", "Click to view the sign after this edit",
                            snapshot, snapshot.side(), false))
                    .append(Component.text("]", NamedTextColor.GRAY));
        }
        else if (bothSides)
        {
            final SignSnapshot snapshot = snapshot(event, oldState);
            message = message.append(Component.text(" [", NamedTextColor.GRAY))
                    .append(viewButton("Front", "Click to view the front of the sign",
                            snapshot, Side.FRONT, false))
                    .append(Component.text(" | ", NamedTextColor.GRAY))
                    .append(viewButton("Back", "Click to view the back of the sign",
                            snapshot, Side.BACK, false))
                    .append(Component.text("]", NamedTextColor.GRAY));
        }
        else
        {
            final SignSnapshot snapshot = snapshot(event, oldState);
            message = message.append(Component.text(" [", NamedTextColor.GRAY))
                    .append(viewButton("View", "Click to view the full sign",
                            snapshot, snapshot.side(), false))
                    .append(Component.text("]", NamedTextColor.GRAY));
        }

        for (final Player admin : plugin.al.getOnlineAdmins())
        {
            if (admin.equals(editor))
            {
                continue;
            }
            final PlayerData data = plugin.pl.getData(admin);
            if (data == null || !data.getSignSpyMode().shows(editorIsAdmin))
            {
                continue;
            }
            FUtil.playerMsg(admin, message);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        if (pendingReverts != null)
        {
            pendingReverts.remove(event.getPlayer().getUniqueId());
        }
    }

    private SignSnapshot snapshot(final SignChangeEvent event, final Sign oldState)
    {
        DyeColor color = DyeColor.BLACK;
        boolean glowing = false;
        DyeColor otherColor = DyeColor.BLACK;
        boolean otherGlowing = false;
        final List<Component> otherLines = new ArrayList<>(LINES_PER_SIDE);
        final List<Component> oldLines = new ArrayList<>(LINES_PER_SIDE);
        if (oldState != null)
        {
            final SignSide side = oldState.getSide(event.getSide());
            color = side.getColor() != null ? side.getColor() : DyeColor.BLACK;
            glowing = side.isGlowingText();
            final SignSide otherSide = oldState.getSide(opposite(event.getSide()));
            otherColor = otherSide.getColor() != null ? otherSide.getColor() : DyeColor.BLACK;
            otherGlowing = otherSide.isGlowingText();
            for (int i = 0; i < LINES_PER_SIDE; i++)
            {
                otherLines.add(otherSide.line(i));
                oldLines.add(side.line(i));
            }
        }
        while (otherLines.size() < LINES_PER_SIDE)
        {
            otherLines.add(Component.empty());
            oldLines.add(Component.empty());
        }

        final List<Component> lines = new ArrayList<>(LINES_PER_SIDE);
        for (int i = 0; i < LINES_PER_SIDE; i++)
        {
            final Component line = event.line(i);
            lines.add(line != null ? line : Component.empty());
        }

        return new SignSnapshot(event.getBlock().getBlockData().clone(), event.getSide(),
                List.copyOf(lines), List.copyOf(oldLines), color, glowing,
                List.copyOf(otherLines), otherColor, otherGlowing);
    }

    private Component viewButton(final String label, final String hover, final SignSnapshot snapshot,
                                 final Side displaySide, final boolean beforeEdit)
    {
        return Component.text(label, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.callback(
                        audience ->
                        {
                            if (audience instanceof Player viewer)
                            {
                                Bukkit.getScheduler().runTask(plugin, FTask.guard("SignSpy/openView",
                                        () -> openView(viewer, snapshot, displaySide, beforeEdit)));
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(ClickCallback.UNLIMITED_USES)
                                .lifetime(VIEW_LIFETIME)
                                .build()))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private void openView(final Player viewer, final SignSnapshot snapshot, final Side displaySide,
                          final boolean beforeEdit)
    {
        if (!viewer.isOnline())
        {
            return;
        }

        revertPending(viewer.getUniqueId());

        final World world = viewer.getWorld();
        final Location loc = viewer.getLocation().toBlockLocation();
        loc.setY(Math.max(world.getMinHeight(), loc.getBlockY() - FAKE_SIGN_DEPTH));

        final BlockData blockData = snapshot.blockData();
        final Sign state = (Sign) blockData.createBlockState();
        final SignSide sideState = state.getSide(snapshot.side());
        final SignSide otherSideState = state.getSide(opposite(snapshot.side()));
        // The other side is untouched by the edit, so it reads the same before and after.
        final List<Component> editedLines = beforeEdit ? snapshot.oldLines() : snapshot.lines();
        for (int i = 0; i < LINES_PER_SIDE; i++)
        {
            sideState.line(i, editedLines.get(i));
            otherSideState.line(i, snapshot.otherLines().get(i));
        }
        sideState.setColor(snapshot.color());
        sideState.setGlowingText(snapshot.glowing());
        otherSideState.setColor(snapshot.otherColor());
        otherSideState.setGlowingText(snapshot.otherGlowing());

        viewer.sendBlockChange(loc, blockData);
        viewer.sendBlockUpdate(loc, state);
        viewer.openVirtualSign(Position.block(loc), displaySide);

        pendingReverts.put(viewer.getUniqueId(), loc);
        Bukkit.getScheduler().runTaskLater(plugin,
                FTask.guard("SignSpy/revertFakeSign", () -> revertIfCurrent(viewer.getUniqueId(), loc)),
                REVERT_DELAY_TICKS);
    }

    private void revertIfCurrent(final UUID viewerId, final Location faked)
    {
        if (pendingReverts != null && faked.equals(pendingReverts.get(viewerId)))
        {
            revertPending(viewerId);
        }
    }

    private void revertPending(final UUID viewerId)
    {
        final Location faked = pendingReverts.remove(viewerId);
        if (faked == null)
        {
            return;
        }
        final Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null || !viewer.getWorld().equals(faked.getWorld()))
        {
            return;
        }
        viewer.sendBlockChange(faked, faked.getBlock().getBlockData());
        if (faked.getBlock().getState() instanceof TileState tile)
        {
            viewer.sendBlockUpdate(faked, tile);
        }
    }
}
