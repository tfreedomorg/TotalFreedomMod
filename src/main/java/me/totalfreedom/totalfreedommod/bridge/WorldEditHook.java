package me.totalfreedom.totalfreedommod.bridge;

import com.google.common.eventbus.Subscribe;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.SessionManager;
import com.sk89q.worldedit.world.block.BlockCategory;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import com.sk89q.worldedit.world.registry.LegacyMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;

/**
 * Loaded only after WorldEditBridge has confirmed the WorldEdit plugin is
 * present — never reference this class from a code path that could run
 * without WorldEdit on the classpath.
 */
public final class WorldEditHook implements Listener
{

    private static final Pattern LIMIT_COMMAND = Pattern.compile(
        "^/(?:limit|/limit)\\s+(\\d+|-1)(?:\\s+(.+))?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BLOCK_TOKEN = Pattern.compile("[a-z0-9_]+(?::[a-z0-9_]+)?");

    private static final Pattern HASH_TOKEN = Pattern.compile("#[a-z0-9_]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern CATEGORY_TOKEN =
            Pattern.compile("#{1,2}\\*?([a-z0-9_.]+(?::[a-z0-9_.]+)?)", Pattern.CASE_INSENSITIVE);

    private static final String[] CLIPBOARD_PATTERNS = {"#clipboard", "#copy", "#fullcopy"};

    private static final Map<String, Integer> RADIUS_COMMANDS = new HashMap<>();

    static
    {
        RADIUS_COMMANDS.put("replacenear", 0);
        RADIUS_COMMANDS.put("drain", 0);
        RADIUS_COMMANDS.put("fixwater", 0);
        RADIUS_COMMANDS.put("fixlava", 0);
        RADIUS_COMMANDS.put("snow", 0);
        RADIUS_COMMANDS.put("thaw", 0);
        RADIUS_COMMANDS.put("ex", 0);
        RADIUS_COMMANDS.put("butcher", 0);
        RADIUS_COMMANDS.put("removeabove", 0);
        RADIUS_COMMANDS.put("removebelow", 0);
        RADIUS_COMMANDS.put("forestgen", 0);
        RADIUS_COMMANDS.put("forest", 0);
        RADIUS_COMMANDS.put("pumpkins", 0);
        // Radius is the second arg (after a pattern/type).
        RADIUS_COMMANDS.put("sphere", 1);
        RADIUS_COMMANDS.put("hsphere", 1);
        RADIUS_COMMANDS.put("cyl", 1);
        RADIUS_COMMANDS.put("hcyl", 1);
        RADIUS_COMMANDS.put("pyramid", 1);
        RADIUS_COMMANDS.put("removenear", 1);
        RADIUS_COMMANDS.put("blob", 1);
        RADIUS_COMMANDS.put("rock", 1);
    }

    private static final String[] BYPASS_NODES = {
        "fawe.bypass",
        "fawe.bypass.regions",
        "fawe.limit.unlimited",
        "fawe.admin",
        "worldedit.limit.unrestricted"
    };

    private static final Set<String> SCHEMATIC_COMMAND_LABELS = Set.of(
        "schem", "schematic", "schematics"
    );

    private static final Set<String> SIZE_SENSITIVE_LABELS = Set.of(
        "copy", "cut", "paste", "stack", "set", "replace", "regen",
        "sphere", "cyl", "pyramid", "smooth", "hsphere", "hcyl", "hpyramid",
        // relighting doesn't call setBlock so we have to guard their volume
        "fixlighting", "fixlight", "removelighting", "removelight"
    );

    private static final int MAX_SWEEP_TRACKED_CHUNKS = 8192;
    private static final long SWEEP_FLUSH_DELAY_TICKS = 60L;
    private static final long MAX_CONTAINER_SCAN_VOLUME = 2_000_000L;
    private static final Set<String> CLIPBOARD_CONTAINER_COMMANDS = Set.of("paste", "place");

    private static final Set<String> BLOCK_ENTITY_IDS = Set.of(
        "enchanting_table", "ender_chest", "beacon", "conduit", "bell", "lectern", "jukebox",
        "spawner", "trial_spawner", "vault", "comparator", "daylight_detector",
        "structure_block", "jigsaw", "end_gateway", "end_portal",
        "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst", "sculk_shrieker",
        "chiseled_bookshelf", "decorated_pot", "suspicious_sand", "suspicious_gravel",
        "brushable_block", "beehive", "bee_nest"
    );

    private static final String[] BLOCK_ENTITY_SUFFIXES = {
        "_sign", "_head", "_skull", "_banner", "_bed", "shulker_box", "campfire", "command_block"
    };

    private final TotalFreedomMod plugin;
    private final Map<UUID, RegionSnapshot> lastSelections = new HashMap<>();
    private final Map<UUID, Integer> playerLimits = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> bypassAttachments = new HashMap<>();
    private final Map<UUID, OpThrottle> opThrottles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> schemSaveTimestamps = new ConcurrentHashMap<>();

    private BukkitTask selectionPollTask;
    private Object editSessionSubscriber;
    private WorldEditPlugin worldEditPlugin;

    public WorldEditHook(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
    }

    public void register()
    {
        worldEditPlugin = resolveWorldEditPlugin();
        if (worldEditPlugin == null)
        {
            // Should not happen — WorldEditBridge gates on provider presence —
            // but bail out cleanly rather than NPE on the first poll tick.
            FLog.warning("WorldEditHook.register(): no WorldEdit / FAWE plugin instance available.");
            return;
        }

        // /limit interception + quit cleanup.
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Wrap every editing session's extent so we can detect operations.
        editSessionSubscriber = new Object()
        {
            @Subscribe
            public void onEditSession(EditSessionEvent event)
            {
                if (event.getStage() != EditSession.Stage.BEFORE_CHANGE)
                {
                    return;
                }
                if (!(event.getActor() instanceof com.sk89q.worldedit.entity.Player))
                {
                    return;
                }
                final com.sk89q.worldedit.entity.Player wePlayer =
                    (com.sk89q.worldedit.entity.Player) event.getActor();

                Extent wrapped = new ProtectedAreaExtent(event.getExtent(), wePlayer, event.getWorld());

                final Player bukkitPlayer = Bukkit.getPlayer(wePlayer.getUniqueId());
                if (bukkitPlayer != null && !plugin.al.isAdmin(bukkitPlayer))
                {
                    wrapped = new LimitExtent(wrapped, wePlayer.getUniqueId(), getLimitFor(wePlayer.getUniqueId()));
                    final int containerCap = getMaxContainers();
                    if (containerCap >= 0)
                    {
                        wrapped = new ContainerLimitExtent(wrapped, wePlayer.getUniqueId(), containerCap);
                    }
                    final int tileCap = getMaxBlockEntities();
                    if (tileCap >= 0)
                    {
                        wrapped = new TileLimitExtent(wrapped, wePlayer.getUniqueId(), tileCap);
                    }
                    final List<String> blockedTypes = getBlockedTypesLower();
                    if (!blockedTypes.isEmpty())
                    {
                        wrapped = new BlockedTypeExtent(wrapped, wePlayer.getUniqueId(), blockedTypes);
                    }
                }

                if (Boolean.TRUE.equals(ConfigEntry.CRASH_CONTAINERS_SCAN_CHUNK_LOAD.getBoolean()))
                {
                    wrapped = new SweepRegionExtent(wrapped, event.getWorld());
                }

                event.setExtent(wrapped);
            }
        };
        WorldEdit.getInstance().getEventBus().register(editSessionSubscriber);

        selectionPollTask = Bukkit.getScheduler().runTaskTimer(plugin,
                FTask.guard("WorldEditHook/pollSelections", this::pollSelections), 1L, 1L);

        // Catch up on already-online players (covers /reload and the 20-tick attach delay).
        for (Player online : Bukkit.getOnlinePlayers())
        {
            refreshBypassNegation(online);
        }

        FLog.info("WorldEdit hook registered.");
    }

    /**
     * Resolves whichever WorldEdit-compatible plugin is loaded. FAWE's main
     * plugin class extends {@link WorldEditPlugin}, so casting works for both
     * stock WorldEdit and FAWE-only installs.
     */
    private static WorldEditPlugin resolveWorldEditPlugin()
    {
        org.bukkit.plugin.Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (we instanceof WorldEditPlugin && we.isEnabled())
        {
            return (WorldEditPlugin) we;
        }
        we = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (we instanceof WorldEditPlugin && we.isEnabled())
        {
            return (WorldEditPlugin) we;
        }
        return null;
    }

    public void unregister()
    {
        if (selectionPollTask != null)
        {
            selectionPollTask.cancel();
            selectionPollTask = null;
        }
        HandlerList.unregisterAll(this);
        // WorldEdit's EventBus offers no public remove(); dropping our reference
        // lets the subscriber be GC'd once the bus releases it on shutdown.
        editSessionSubscriber = null;
        lastSelections.clear();
        opThrottles.clear();

        for (Map.Entry<UUID, PermissionAttachment> e : bypassAttachments.entrySet())
        {
            try
            {
                e.getValue().remove();
            }
            catch (Throwable ignored)
            {
            }
        }
        bypassAttachments.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        refreshBypassNegation(event.getPlayer());
    }

    public void refreshBypassNegation(Player player)
    {
        if (player == null || !player.isOnline())
        {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final boolean shouldNegate = !plugin.al.isAdmin(player);
        final PermissionAttachment existing = bypassAttachments.get(uuid);

        if (!shouldNegate)
        {
            if (existing != null)
            {
                try
                {
                    existing.remove();
                }
                catch (Throwable ignored)
                {
                }
                bypassAttachments.remove(uuid);
            }
            return;
        }

        if (existing != null)
        {
            return;
        }
        try
        {
            final PermissionAttachment att = player.addAttachment(plugin);
            for (String node : BYPASS_NODES)
            {
                att.setPermission(node, false);
            }
            bypassAttachments.put(uuid, att);
        }
        catch (Throwable t)
        {
            FLog.warning("Failed to apply WorldEdit bypass negation for " + player.getName() + ": " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event)
    {
        if (checkOpThrottle(event))
        {
            return;
        }

        if (checkPatternComplexity(event))
        {
            return;
        }

        if (checkCategoryTags(event))
        {
            return;
        }

        if (checkPatternTypes(event))
        {
            return;
        }

        if (checkClipboardPattern(event))
        {
            return;
        }

        if (checkRadiusCommand(event))
        {
            return;
        }

        final Matcher m = LIMIT_COMMAND.matcher(event.getMessage());
        if (!m.matches())
        {
            return;
        }

        final int limit;
        try
        {
            limit = Integer.parseInt(m.group(1));
        }
        catch (NumberFormatException ex)
        {
            return;
        }
        final String targetName = m.group(2);

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        if (targetName != null && !targetName.equalsIgnoreCase(player.getName()))
        {
            player.sendMessage(Component.text("Only admins can change the limit for other players!", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        final Integer maxLimitObj = ConfigEntry.WORLDEDIT_LIMIT_MAX.getInteger();
        final int maxLimit = (maxLimitObj == null) ? -1 : maxLimitObj;
        if (maxLimit < 0)
        {
            return;
        }

        if (limit < 0 || limit > maxLimit)
        {
            if (ConfigEntry.WORLDEDIT_DEOP_ON_LIMIT_ABUSE.getBoolean())
            {
                player.setOp(false);
                FUtil.bcastMsg(player.getName() + " tried to set their WorldEdit limit to "
                    + limit + " and has been de-opped", NamedTextColor.RED);
            }
            else
            {
                FUtil.bcastMsg(player.getName() + " tried to set their WorldEdit limit to "
                    + limit, NamedTextColor.RED);
            }
            event.setCancelled(true);
            player.sendMessage(Component.text("You cannot set your limit higher than "
                + maxLimit + " or to -1!", NamedTextColor.RED));
            return;
        }

        setPlayerLimit(player.getUniqueId(), limit);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        final UUID uuid = event.getPlayer().getUniqueId();
        lastSelections.remove(uuid);
        playerLimits.remove(uuid);
        opThrottles.remove(uuid);
        final Long schemTs = schemSaveTimestamps.get(uuid);
        if (schemTs == null || System.currentTimeMillis() - schemTs >= getSchemSaveCooldownMs())
        {
            schemSaveTimestamps.remove(uuid);
        }
        final PermissionAttachment att = bypassAttachments.remove(uuid);
        if (att != null)
        {
            try
            {
                att.remove();
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    public void setPlayerLimit(UUID uuid, int limit)
    {
        final Integer maxLimitObj = ConfigEntry.WORLDEDIT_LIMIT_MAX.getInteger();
        final int maxLimit = (maxLimitObj == null) ? -1 : maxLimitObj;
        if (limit < 0)
        {
            return;
        }
        final int effective = (maxLimit < 0) ? limit : Math.min(limit, maxLimit);
        playerLimits.put(uuid, effective);
    }

    public int getLimitFor(UUID uuid)
    {
        final int fallback = ConfigEntry.WORLDEDIT_LIMIT_MAX.getInteger(Integer.MAX_VALUE);
        final Integer stored = playerLimits.get(uuid);
        return (stored == null) ? fallback : stored;
    }

    private static boolean throttleEnabled()
    {
        return ConfigEntry.WORLDEDIT_THROTTLE_ENABLED.getBoolean(true);
    }

    private static int throttleMaxOps()
    {
        final int v = ConfigEntry.WORLDEDIT_THROTTLE_MAX_OPS.getInteger(5);
        return v < 0 ? 5 : v;
    }

    private static long throttleWindowMs()
    {
        final int v = ConfigEntry.WORLDEDIT_THROTTLE_TIME_WINDOW.getInteger(1000);
        return v <= 0 ? 1000L : v;
    }

    private static int throttleEjectThreshold()
    {
        // Negative disables auto-eject (throttle only).
        return ConfigEntry.WORLDEDIT_THROTTLE_MAX_CANCELLED_OPS.getInteger(5);
    }

    private static boolean isWorldEditOp(String message)
    {
        if (message == null || message.length() < 2 || message.charAt(0) != '/')
        {
            return false;
        }
        if (message.charAt(1) == '/')
        {
            return true;
        }
        String label = message.substring(1);
        final int sp = label.indexOf(' ');
        if (sp >= 0)
        {
            label = label.substring(0, sp);
        }
        final int colon = label.indexOf(':');
        if (colon <= 0)
        {
            return false;
        }
        final String ns = label.substring(0, colon).toLowerCase(Locale.ROOT);
        return ns.equals("worldedit") || ns.equals("fawe") || ns.equals("we");
    }

    private boolean checkOpThrottle(PlayerCommandPreprocessEvent event)
    {
        if (!throttleEnabled())
        {
            return false;
        }
        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }
        if (!isWorldEditOp(event.getMessage()))
        {
            return false;
        }

        final UUID uuid = player.getUniqueId();
        final OpThrottle throttle = opThrottles.computeIfAbsent(uuid, k -> new OpThrottle());
        final long now = System.currentTimeMillis();
        final long window = throttleWindowMs();

        // Roll the rate window.
        if (now - throttle.windowStart > window)
        {
            throttle.windowStart = now;
            throttle.count = 0;
        }
        throttle.count++;
        if (throttle.count <= throttleMaxOps())
        {
            return false;
        }

        event.setCancelled(true);

        if (now - throttle.lastViolation > window * 5L)
        {
            throttle.violations = 0;
        }
        throttle.lastViolation = now;
        throttle.violations++;

        final int ejectThreshold = throttleEjectThreshold();
        if (ejectThreshold >= 0 && throttle.violations >= ejectThreshold)
        {
            opThrottles.remove(uuid);
            FUtil.bcastMsg(player.getName()
                + " was automatically ejected for spamming WorldEdit operations.", NamedTextColor.RED);
            plugin.ae.autoEject(player, "Kicked for spamming WorldEdit operations.");
        }
        else if (now - throttle.lastWarn > window)
        {
            throttle.lastWarn = now;
            player.sendMessage(Component.text(
                "You are issuing WorldEdit operations too quickly. Slow down.",
                NamedTextColor.RED));
        }
        return true;
    }

    private boolean checkCategoryTags(PlayerCommandPreprocessEvent event)
    {
        if (!ConfigEntry.WORLDEDIT_BLOCK_CATEGORY_TAGS.getBoolean(true))
        {
            return false;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final String message = event.getMessage();
        if (!isPatternCapableOp(message))
        {
            return false;
        }

        final List<String> tokens = tokenizeArgs(stripSlashes(message));
        for (int i = 1; i < tokens.size(); i++)
        {
            final String arg = tokens.get(i).toLowerCase(Locale.ROOT);
            if (CATEGORY_TOKEN.matcher(arg).find())
            {
                event.setCancelled(true);
                player.sendMessage(Component.text(
                    "Block-category tags (##…) cannot be used in your WorldEdit operations.",
                    NamedTextColor.RED));
                FLog.warning(player.getName()
                    + " tried to use a block-category tag in a W/E operation: " + message);
                return true;
            }
        }
        return false;
    }

    private boolean checkPatternTypes(PlayerCommandPreprocessEvent event)
    {
        final List<String> blocked = getBlockedTypesLower();
        if (blocked.isEmpty())
        {
            return false;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final String message = event.getMessage();
        if (!isWorldEditOp(message))
        {
            return false;
        }

        final List<String> tokens = tokenizeArgs(stripSlashes(message));
        for (int i = 1; i < tokens.size(); i++)
        {
            final String arg = tokens.get(i).toLowerCase(Locale.ROOT);

            final String tagHit = firstBlockedCategoryMember(arg, blocked);
            if (tagHit != null)
            {
                cancelBlockedType(event, player, tagHit, message);
                return true;
            }

            final Matcher hash = HASH_TOKEN.matcher(arg);
            while (hash.find())
            {
                final int start = hash.start();
                if (start + 1 < arg.length() && arg.charAt(start + 1) == '#')
                {
                    continue;
                }
                if (start > 0 && arg.charAt(start - 1) == '#')
                {
                    continue;
                }
                final String special = hash.group();
                if (isBlockedSpecialPattern(special, blocked))
                {
                    cancelBlockedType(event, player, special, message);
                    return true;
                }
            }

            final Matcher m = BLOCK_TOKEN.matcher(arg);
            while (m.find())
            {
                final String raw = m.group();

                final String legacyId = resolveLegacyBlockId(raw);
                if (legacyId != null)
                {
                    for (String entry : blocked)
                    {
                        if (!entry.isEmpty() && entry.charAt(0) == '#')
                        {
                            continue;
                        }
                        if (blockedIdMatches(legacyId, entry))
                        {
                            cancelBlockedType(event, player, raw + " (" + legacyId + ")", message);
                            return true;
                        }
                    }
                    continue;
                }

                String id = raw;
                final int colon = id.indexOf(':');
                if (colon >= 0)
                {
                    id = id.substring(colon + 1);
                }
                for (String entry : blocked)
                {
                    if (!entry.isEmpty() && entry.charAt(0) == '#')
                    {
                        continue;
                    }
                    if (blockedIdMatches(id, entry))
                    {
                        cancelBlockedType(event, player, id, message);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isBlockedSpecialPattern(String special, List<String> blockedLower)
    {
        for (String entry : blockedLower)
        {
            if (entry.length() < 2 || entry.charAt(0) != '#' || entry.charAt(1) == '#')
            {
                continue;
            }
            if (blockedIdMatches(special, entry))
            {
                return true;
            }
        }
        return false;
    }

    private static void cancelBlockedType(PlayerCommandPreprocessEvent event, Player player, String id, String message)
    {
        event.setCancelled(true);
        player.sendMessage(Component.text(
            "The block type '" + id + "' cannot be used in your operation.",
            NamedTextColor.RED));
        FLog.warning(player.getName() + " tried to use a disallowed W/E block type (" + id
            + "): " + message);
    }

    private static boolean blockedIdMatches(String id, String entry)
    {
        if (entry.indexOf('*') >= 0)
        {
            final String needle = entry.replace("*", "");
            return !needle.isEmpty() && id.contains(needle);
        }
        return id.equals(entry);
    }

    private static String resolveLegacyBlockId(String token)
    {
        final int id;
        final int data;
        final int colon = token.indexOf(':');
        try
        {
            if (colon >= 0)
            {
                id = Integer.parseInt(token.substring(0, colon));
                data = Integer.parseInt(token.substring(colon + 1));
            }
            else
            {
                id = Integer.parseInt(token);
                data = -1;
            }
        }
        catch (NumberFormatException notNumeric)
        {
            return null;
        }

        try
        {
            final com.sk89q.worldedit.world.block.BlockState state = (data >= 0)
                ? LegacyMapper.getInstance().getBlockFromLegacy(id, data)
                : LegacyMapper.getInstance().getBlockFromLegacy(id);
            if (state == null)
            {
                return null;
            }
            final BlockType type = state.getBlockType();
            if (type == null)
            {
                return null;
            }
            String bid = type.id().toLowerCase(Locale.ROOT);
            final int c = bid.indexOf(':');
            return (c >= 0) ? bid.substring(c + 1) : bid;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private boolean checkClipboardPattern(PlayerCommandPreprocessEvent event)
    {
        if (!ConfigEntry.WORLDEDIT_BLOCK_CLIPBOARD_PATTERN.getBoolean(true))
        {
            return false;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final String message = event.getMessage();
        if (!isPatternCapableOp(message))
        {
            return false;
        }

        final int sp = message.indexOf(' ');
        if (sp < 0)
        {
            return false;
        }
        if (!referencesClipboard(message.substring(sp + 1)))
        {
            return false;
        }

        event.setCancelled(true);
        player.sendMessage(Component.text(
            "The clipboard pattern may not be used in this context. Please use //paste to paste your clipboard.",
            NamedTextColor.RED));
        FLog.warning(player.getName() + " tried to use the clipboard pattern in an operation: " + message);
        return true;
    }

    private static boolean isPatternCapableOp(String message)
    {
        if (message == null || message.length() < 2 || message.charAt(0) != '/')
        {
            return false;
        }
        if (isWorldEditOp(message))
        {
            return true;
        }
        final int sp = message.indexOf(' ');
        final String firstToken = (sp < 0) ? message : message.substring(0, sp);
        final String label = normalizeCommandLabel(firstToken);
        return label.equals("br") || label.equals("brush");
    }

    private static boolean referencesClipboard(String args)
    {
        if (args == null || args.isEmpty())
        {
            return false;
        }
        final Matcher m = HASH_TOKEN.matcher(args.toLowerCase(Locale.ROOT));
        while (m.find())
        {
            if (isClipboardToken(m.group()))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isClipboardToken(String tokenLower)
    {
        for (String canonical : CLIPBOARD_PATTERNS)
        {
            if (canonical.startsWith(tokenLower) || tokenLower.startsWith(canonical))
            {
                return true;
            }
        }
        return false;
    }

    private boolean checkPatternComplexity(PlayerCommandPreprocessEvent event)
    {
        final Integer maxObj = ConfigEntry.WORLDEDIT_MAX_PATTERN_BLOCKS.getInteger();
        if (maxObj == null || maxObj < 0)
        {
            return false;
        }
        final int max = maxObj;

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final String message = event.getMessage();
        if (!isWorldEditOp(message))
        {
            return false;
        }

        final List<String> tokens = tokenizeArgs(stripSlashes(message));
        for (int i = 1; i < tokens.size(); i++)
        {
            final String token = tokens.get(i);
            if (isFlag(token))
            {
                continue;
            }
            final int components = countPatternComponents(token);
            if (components > max)
            {
                event.setCancelled(true);
                player.sendMessage(Component.text(
                    "You must use less than " + max
                        + " different kinds of block types in your operation.",
                    NamedTextColor.RED));
                return true;
            }
        }
        return false;
    }

    /**
     * groups "(...)", or "{...}" — do not count, so "oak_log[axis=x]" is one
     * block, while "a,b,c" is three.
     */
    private static int countPatternComponents(String token)
    {
        if (token == null || token.isEmpty())
        {
            return 0;
        }
        int depth = 0;
        int components = 1;
        for (int i = 0; i < token.length(); i++)
        {
            final char c = token.charAt(i);
            if (c == '[')
            {
                depth++;
            }
            else if (c == ']')
            {
                if (depth > 0)
                {
                    depth--;
                }
            }
            else if ((c == ',' || c == '&') && depth == 0)
            {
                components++;
            }
        }
        return components;
    }

    private boolean checkRadiusCommand(PlayerCommandPreprocessEvent event)
    {
        final Integer maxObj = ConfigEntry.WORLDEDIT_RADIUS_MAX.getInteger();
        if (maxObj == null || maxObj < 0)
        {
            return false;
        }
        final int max = maxObj;

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final String msg = event.getMessage();
        if (msg.length() < 2 || msg.charAt(0) != '/')
        {
            return false;
        }

        final List<String> tokens = tokenizeArgs(stripSlashes(msg));
        if (tokens.isEmpty() || tokens.get(0).isEmpty())
        {
            return false;
        }

        final Integer argIdxObj = RADIUS_COMMANDS.get(normalizeCommandLabel(tokens.get(0)));
        if (argIdxObj == null)
        {
            return false;
        }

        final List<String> positional = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++)
        {
            if (!isFlag(tokens.get(i)))
            {
                positional.add(tokens.get(i));
            }
        }
        if (positional.size() <= argIdxObj)
        {
            return false;
        }

        final int radius = parseRadius(positional.get(argIdxObj));
        if (radius < 0 || radius <= max)
        {
            return false;
        }

        event.setCancelled(true);
        player.sendMessage(Component.text(
            "You must use a radius of " + max + " or lesser.",
            NamedTextColor.RED));
        return true;
    }

    private static int parseRadius(String token)
    {
        if (token == null || token.isEmpty())
        {
            return -1;
        }
        double max = -1;
        for (String part : token.split(","))
        {
            try
            {
                final double v = Double.parseDouble(part.trim());
                if (v > max)
                {
                    max = v;
                }
            }
            catch (NumberFormatException ignored)
            {
                return -1;
            }
        }
        if (max < 0)
        {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(max));
    }

    private static String stripSlashes(String message)
    {
        int i = 0;
        if (i < message.length() && message.charAt(i) == '/')
        {
            i++;
        }
        if (i < message.length() && message.charAt(i) == '/')
        {
            i++;
        }
        return message.substring(i);
    }

    private static List<String> tokenizeArgs(String body)
    {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < body.length(); i++)
        {
            final char c = body.charAt(i);
            if (quote != 0)
            {
                if (c == quote)
                {
                    quote = 0;
                }
                else
                {
                    cur.append(c);
                }
                inToken = true;
            }
            else if (c == '"' || c == '\'')
            {
                quote = c;
                inToken = true;
            }
            else if (Character.isWhitespace(c))
            {
                if (inToken)
                {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            }
            else
            {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken)
        {
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean isFlag(String token)
    {
        if (token.length() < 2 || token.charAt(0) != '-')
        {
            return false;
        }
        final char c = token.charAt(1);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String firstBlockedCategoryMember(String argLower, List<String> blockedLower)
    {
        final Matcher m = CATEGORY_TOKEN.matcher(argLower);
        while (m.find())
        {
            final String categoryId = m.group(1).toLowerCase(Locale.ROOT);
            if (isBlockedCategoryName(categoryId, blockedLower))
            {
                return "##" + categoryId;
            }

            final Set<BlockType> members = resolveCategoryMembers(categoryId);
            if (members == null)
            {
                continue;
            }
            for (BlockType type : members)
            {
                String id = type.id().toLowerCase(Locale.ROOT);
                final int colon = id.indexOf(':');
                if (colon >= 0)
                {
                    id = id.substring(colon + 1);
                }
                for (String entry : blockedLower)
                {
                    if (blockedIdMatches(id, entry))
                    {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isBlockedCategoryName(String categoryId, List<String> blockedLower)
    {
        for (String entry : blockedLower)
        {
            if (entry.isEmpty() || entry.charAt(0) != '#')
            {
                continue;
            }
            String name = entry;
            while (!name.isEmpty() && name.charAt(0) == '#')
            {
                name = name.substring(1);
            }
            if (!name.isEmpty() && name.charAt(0) == '*')
            {
                name = name.substring(1);
            }
            final int colon = name.indexOf(':');
            if (colon >= 0)
            {
                name = name.substring(colon + 1);
            }
            if (!name.isEmpty() && blockedIdMatches(categoryId, name))
            {
                return true;
            }
        }
        return false;
    }

    private static Set<BlockType> resolveCategoryMembers(String rawId)
    {
        BlockCategory category = safeGetCategory(rawId);
        if (category == null && rawId.indexOf(':') < 0)
        {
            category = safeGetCategory("minecraft:" + rawId);
        }
        return (category == null) ? null : category.getAll();
    }

    private static BlockCategory safeGetCategory(String id)
    {
        try
        {
            return BlockCategory.REGISTRY.get(id);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * @return the configured max selection volume, or -1 if disabled / unset.
     */
    private static long getMaxSelectionVolume()
    {
        final Integer raw = ConfigEntry.WORLDEDIT_MAX_SELECTION_VOLUME.getInteger();
        if (raw == null || raw <= 0)
        {
            return -1L;
        }
        return raw.longValue();
    }

    private static int getMaxContainers()
    {
        final Integer raw = ConfigEntry.WORLDEDIT_MAX_CONTAINERS.getInteger();
        if (raw == null || raw < 0)
        {
            return -1;
        }
        return raw;
    }

    private static int getMaxBlockEntities()
    {
        final Integer raw = ConfigEntry.WORLDEDIT_MAX_BLOCK_ENTITIES_PER_OP.getInteger();
        if (raw == null || raw < 0)
        {
            return -1;
        }
        return raw;
    }

    private static List<String> getBlockedTypesLower()
    {
        final List<String> raw = ConfigEntry.WORLDEDIT_BLOCKED_BLOCK_TYPES.getStringList();
        if (raw == null || raw.isEmpty())
        {
            return List.of();
        }
        final List<String> out = new ArrayList<>(raw.size());
        for (String entry : raw)
        {
            if (entry != null && !entry.isEmpty())
            {
                out.add(entry.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static long getMaxSchematicSaveBytes()
    {
        final Integer raw = ConfigEntry.WORLDEDIT_MAX_SCHEM_SAVE_KB.getInteger();
        if (raw == null || raw <= 0)
        {
            return -1L;
        }
        return raw.longValue() * 1024L;
    }

    private static long getSchemSaveCooldownMs()
    {
        final Integer raw = ConfigEntry.WORLDEDIT_SCHEM_SAVE_COOLDOWN.getInteger();
        if (raw == null || raw <= 0)
        {
            return 0L;
        }
        return raw.longValue() * 1000L;
    }

    private static String formatDuration(long ms)
    {
        final long totalSeconds = (ms + 999L) / 1000L;
        final long minutes = totalSeconds / 60L;
        final long seconds = totalSeconds % 60L;
        if (minutes > 0 && seconds > 0)
        {
            return minutes + "m " + seconds + "s";
        }
        if (minutes > 0)
        {
            return minutes + "m";
        }
        return seconds + "s";
    }

    /**
     * Calculates the theoretical amount of modified blocks within a cuboid bound.
     * Prevents the exploitation of maths along extreme coordinates (e.g. "Infinity").
     */
    private static long safeSelectionVolume(BlockVector3 min, BlockVector3 max)
    {
        final long dx = (long) max.x() - min.x() + 1L;
        final long dy = (long) max.y() - min.y() + 1L;
        final long dz = (long) max.z() - min.z() + 1L;
        // normalized min/max always yield spans >= 1
        if (dx <= 0L || dy <= 0L || dz <= 0L)
        {
            return Long.MAX_VALUE;
        }
        try
        {
            return Math.multiplyExact(Math.multiplyExact(dx, dy), dz);
        }
        catch (ArithmeticException overflow)
        {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Strip leading slashes and any `worldedit:` / `fawe:` namespace prefix,
     * lowercased. Used to match command labels against {@link #SIZE_SENSITIVE_LABELS}.
     */
    private static String normalizeCommandLabel(String firstToken)
    {
        int i = 0;
        while (i < firstToken.length() && firstToken.charAt(i) == '/')
        {
            i++;
        }
        String s = firstToken.substring(i).toLowerCase(Locale.ROOT);
        final int colon = s.indexOf(':');
        if (colon >= 0)
        {
            s = s.substring(colon + 1);
        }
        return s;
    }

    /**
     * Runs at LOWEST priority so we cancel before WorldEdit allocates a
     * clipboard / region copy from {@code region.getVolume()}.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSizeSensitiveCommand(PlayerCommandPreprocessEvent event)
    {
        final long cap = getMaxSelectionVolume();
        if (cap <= 0)
        {
            return;
        }
        final String msg = event.getMessage();
        if (msg == null || msg.isEmpty())
        {
            return;
        }
        final int sp = msg.indexOf(' ');
        final String firstToken = (sp < 0) ? msg : msg.substring(0, sp);
        final String label = normalizeCommandLabel(firstToken);
        if (!SIZE_SENSITIVE_LABELS.contains(label))
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }
        if (worldEditPlugin == null)
        {
            return;
        }

        try
        {
            final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            final Region region;
            try
            {
                region = session.getSelection(wePlayer.getWorld());
            }
            catch (IncompleteRegionException ex)
            {
                return;
            }
            final long volume = safeSelectionVolume(
                region.getMinimumPoint(), region.getMaximumPoint());
            if (volume <= cap)
            {
                return;
            }

            event.setCancelled(true);
            player.sendMessage(Component.text(
                "Your WorldEdit selection (" + volume + " blocks) exceeds the max of "
                    + cap + ". Operation blocked.",
                NamedTextColor.RED));
            FLog.warning("Blocked oversized WorldEdit op from " + player.getName()
                + " (volume=" + volume + ", cap=" + cap + "): " + msg);
            try
            {
                session.getRegionSelector(wePlayer.getWorld()).clear();
                lastSelections.remove(player.getUniqueId());
            }
            catch (Throwable ignored)
            {
            }
        }
        catch (Throwable t)
        {
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSchematicSaveCommand(PlayerCommandPreprocessEvent event)
    {
        final String msg = event.getMessage();
        if (msg == null || msg.length() < 2 || msg.charAt(0) != '/')
        {
            return;
        }
        final List<String> tokens = tokenizeArgs(stripSlashes(msg));
        if (tokens.size() < 2)
        {
            return;
        }
        if (!SCHEMATIC_COMMAND_LABELS.contains(normalizeCommandLabel(tokens.get(0))))
        {
            return;
        }
        if (!"save".equalsIgnoreCase(tokens.get(1)))
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player) || worldEditPlugin == null)
        {
            return;
        }

        final long cooldownMs = getSchemSaveCooldownMs();
        if (cooldownMs > 0)
        {
            final UUID uuid = player.getUniqueId();
            final long now = System.currentTimeMillis();
            final Long last = schemSaveTimestamps.get(uuid);
            if (last != null && now - last < cooldownMs)
            {
                event.setCancelled(true);
                player.sendMessage(Component.text(
                    "You can only save one schematic every " + formatDuration(cooldownMs)
                        + ". Please wait " + formatDuration(cooldownMs - (now - last)) + ".",
                    NamedTextColor.RED));
                return;
            }
            schemSaveTimestamps.put(uuid, now);
        }

        enforceSchematicSaveSize(event, player, msg);
    }

    private void enforceSchematicSaveSize(PlayerCommandPreprocessEvent event, Player player, String msg)
    {
        final long capBytes = getMaxSchematicSaveBytes();
        if (capBytes <= 0)
        {
            return;
        }

        try
        {
            final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            final Clipboard clipboard;
            try
            {
                clipboard = session.getClipboard().getClipboard();
            }
            catch (EmptyClipboardException ex)
            {
                return;
            }

            final CountingLimitStream out = new CountingLimitStream(capBytes);
            boolean exceeded = false;
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out))
            {
                writer.write(clipboard);
            }
            catch (CountingLimitStream.LimitExceeded ex)
            {
                exceeded = true;
            }

            if (!exceeded)
            {
                return;
            }

            event.setCancelled(true);
            session.setClipboard(null);
            final long capKb = capBytes / 1024L;
            player.sendMessage(Component.text(
                "Your schematic contains too much data in order to be saved.",
                NamedTextColor.RED));
            FLog.warning("Blocked oversized schematic save from " + player.getName()
                + " (cap=" + capKb + " KB): " + msg);
        }
        catch (Throwable t)
        {
            FLog.warning("Failed to check schematic save size for " + player.getName()
                + ": " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onContainerCommand(PlayerCommandPreprocessEvent event)
    {
        final int cap = getMaxContainers();
        if (cap < 0)
        {
            return;
        }

        final String msg = event.getMessage();
        if (msg == null || msg.length() < 2 || msg.charAt(0) != '/')
        {
            return;
        }
        String body = msg.substring(1);
        if (!body.isEmpty() && body.charAt(0) == '/')
        {
            body = body.substring(1);
        }
        final String[] tokens = body.trim().split("\\s+");
        if (tokens.length == 0)
        {
            return;
        }

        if (!CLIPBOARD_CONTAINER_COMMANDS.contains(normalizeCommandLabel(tokens[0])))
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player) || worldEditPlugin == null)
        {
            return;
        }

        try
        {
            final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            enforceClipboardContainers(event, player, session, cap);
        }
        catch (EmptyClipboardException ex)
        {
        }
        catch (Throwable t)
        {
            FLog.warning("Failed to check WorldEdit container command for " + event.getPlayer().getName()
                + ": " + t.getMessage());
        }
    }

    private void enforceClipboardContainers(PlayerCommandPreprocessEvent event, Player player, LocalSession session, int cap)
        throws EmptyClipboardException
    {
        final Clipboard clipboard = session.getClipboard().getClipboard();
        final Region region = clipboard.getRegion();
        if (region.getVolume() > MAX_CONTAINER_SCAN_VOLUME)
        {
            event.setCancelled(true);
            player.sendMessage(Component.text("Your clipboard contains too much data to paste.", NamedTextColor.RED));
            FLog.warning("Blocked oversized volume clipboard paste from " + player.getName()
                + " (" + region.getVolume() + ")");
            return;
        }

        final int containers = countContainersInClipboard(clipboard, region);
        if (containers > cap)
        {
            event.setCancelled(true);
            player.sendMessage(Component.text(
                "Your clipboard contains too much container data to paste.",
                NamedTextColor.RED));
            FLog.warning("Blocked oversized container clipboard paste from " + player.getName()
                + " (" + containers + " containers)");
        }
    }

    private static int countContainersInClipboard(Clipboard clipboard, Region region)
    {
        int count = 0;
        for (BlockVector3 pos : region)
        {
            if (isContainerBlock(clipboard.getBlock(pos)))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Roll back the last {@code count} WorldEdit operations from {@code player}.
     */
    public void undo(Player player, int count)
    {
        if (worldEditPlugin == null || player == null || count <= 0)
        {
            return;
        }
        try
        {
            final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
            final LocalSession session = WorldEdit.getInstance().getSessionManager().get(wePlayer);
            for (int i = 0; i < count; i++)
            {
                if (session.undo(null, wePlayer) == null)
                {
                    break;
                }
            }
        }
        catch (Throwable t)
        {
            FLog.severe(t);
        }
    }

    public int cancel(Player player)
    {
        if (worldEditPlugin == null || player == null)
        {
            return 0;
        }
        try
        {
            final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(player);
            final java.lang.reflect.Method cancelMethod =
                wePlayer.getClass().getMethod("cancel", boolean.class);
            final Object result = cancelMethod.invoke(wePlayer, true);
            return (result instanceof Integer) ? (Integer) result : 0;
        }
        catch (NoSuchMethodException ex)
        {
            return 0;
        }
        catch (Throwable t)
        {
            FLog.warning("Failed to cancel WorldEdit operations for "
                + player.getName() + ": " + t.getMessage());
            return 0;
        }
    }

    private void pollSelections()
    {
        final SessionManager sessions = WorldEdit.getInstance().getSessionManager();
        for (Player bukkitPlayer : Bukkit.getOnlinePlayers())
        {
            if (plugin.al.isAdmin(bukkitPlayer))
            {
                continue;
            }
            try
            {
                final com.sk89q.worldedit.entity.Player wePlayer = worldEditPlugin.wrapPlayer(bukkitPlayer);
                final UUID uuid = wePlayer.getUniqueId();
                final LocalSession session = sessions.get(wePlayer);

                final Region region;
                try
                {
                    region = session.getSelection(wePlayer.getWorld());
                }
                catch (IncompleteRegionException ex)
                {
                    lastSelections.remove(uuid);
                    continue;
                }

                final BlockVector3 min = region.getMinimumPoint();
                final BlockVector3 max = region.getMaximumPoint();
                final RegionSnapshot snap = new RegionSnapshot(min, max);
                final RegionSnapshot prev = lastSelections.get(uuid);
                if (prev != null && prev.equals(snap))
                {
                    continue;
                }

                lastSelections.put(uuid, snap);

                final long cap = getMaxSelectionVolume();
                if (cap > 0)
                {
                    final long volume = safeSelectionVolume(min, max);
                    if (volume > cap)
                    {
                        bukkitPlayer.sendMessage(Component.text(
                            "Your WorldEdit selection (" + volume + " blocks) exceeds the max of "
                                + cap + ". Selection cleared.",
                            NamedTextColor.RED));
                        session.getRegionSelector(wePlayer.getWorld()).clear();
                        lastSelections.remove(uuid);
                        continue;
                    }
                }

                final World world = Bukkit.getWorld(wePlayer.getWorld().getName());
                if (world == null)
                {
                    continue;
                }

                if (plugin.pa.doesRegionOverlapWithProtectedArea(
                    BukkitAdapter.adapt(world, min),
                    BukkitAdapter.adapt(world, max),
                    world))
                {
                    bukkitPlayer.sendMessage(Component.text("The region that you selected contained a protected area. Selection cleared.", NamedTextColor.RED));
                    session.getRegionSelector(wePlayer.getWorld()).clear();
                    lastSelections.remove(uuid);
                }
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private abstract static class PathCompleteExtent extends AbstractDelegateExtent
    {

        PathCompleteExtent(Extent parent)
        {
            super(parent);
        }

        public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block)
            throws WorldEditException
        {
            return setBlock(BlockVector3.at(x, y, z), block);
        }
    }

    private final class ProtectedAreaExtent extends PathCompleteExtent
    {

        private final com.sk89q.worldedit.entity.Player wePlayer;
        private final com.sk89q.worldedit.world.World weWorld;
        private boolean checked = false;
        private boolean denied = false;
        private BlockVector3 minPos;
        private BlockVector3 maxPos;

        ProtectedAreaExtent(Extent parent,
                            com.sk89q.worldedit.entity.Player wePlayer,
                            com.sk89q.worldedit.world.World weWorld)
        {
            super(parent);
            this.wePlayer = wePlayer;
            this.weWorld = weWorld;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            if (minPos == null)
            {
                minPos = pos;
            }
            else if (pos.x() < minPos.x() || pos.y() < minPos.y() || pos.z() < minPos.z())
            {
                minPos = BlockVector3.at(
                    Math.min(minPos.x(), pos.x()),
                    Math.min(minPos.y(), pos.y()),
                    Math.min(minPos.z(), pos.z()));
            }

            if (maxPos == null)
            {
                maxPos = pos;
            }
            else if (pos.x() > maxPos.x() || pos.y() > maxPos.y() || pos.z() > maxPos.z())
            {
                maxPos = BlockVector3.at(
                    Math.max(maxPos.x(), pos.x()),
                    Math.max(maxPos.y(), pos.y()),
                    Math.max(maxPos.z(), pos.z()));
            }

            if (!checked)
            {
                checked = true;
                denied = shouldDeny();
            }
            if (denied)
            {
                return false;
            }
            return super.setBlock(pos, block);
        }

        private boolean shouldDeny()
        {
            final Player bukkitPlayer = Bukkit.getPlayer(wePlayer.getUniqueId());
            if (bukkitPlayer == null)
            {
                return false;
            }
            if (plugin.al.isAdmin(bukkitPlayer))
            {
                return false;
            }

            final World world = Bukkit.getWorld(weWorld.getName());
            if (world == null || minPos == null || maxPos == null)
            {
                return false;
            }

            if (plugin.pa.doesRegionOverlapWithProtectedArea(
                BukkitAdapter.adapt(world, minPos),
                BukkitAdapter.adapt(world, maxPos),
                world))
            {
                bukkitPlayer.sendMessage(Component.text("You cannot perform WorldEdit operations in a protected area!", NamedTextColor.RED));
                return true;
            }
            return false;
        }
    }

    private final class LimitExtent extends PathCompleteExtent
    {

        private final UUID uuid;
        private final int limit;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicBoolean warned = new AtomicBoolean();

        LimitExtent(Extent parent, UUID uuid, int limit)
        {
            super(parent);
            this.uuid = uuid;
            this.limit = limit;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            if (count.incrementAndGet() > limit)
            {
                if (warned.compareAndSet(false, true))
                {
                    Bukkit.getScheduler().runTask(plugin, () ->
                    {
                        final Player p = Bukkit.getPlayer(uuid);
                        if (p != null)
                        {
                            p.sendMessage(Component.text(
                                "WorldEdit limit reached (" + limit + " blocks). Operation halted.",
                                NamedTextColor.RED));
                        }
                    });
                }
                throw new MaxChangedBlocksException(limit);
            }
            return super.setBlock(pos, block);
        }
    }

    private final class ContainerLimitExtent extends PathCompleteExtent
    {

        private final UUID uuid;
        private final int cap;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicBoolean warned = new AtomicBoolean();

        ContainerLimitExtent(Extent parent, UUID uuid, int cap)
        {
            super(parent);
            this.uuid = uuid;
            this.cap = cap;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            final BlockType type = block.getBlockType();
            if (type != null)
            {
                final BlockMaterial mat = type.getMaterial();
                if (mat != null && mat.hasContainer() && count.incrementAndGet() > cap)
                {
                    if (warned.compareAndSet(false, true))
                    {
                        Bukkit.getScheduler().runTask(plugin, () ->
                        {
                            final Player p = Bukkit.getPlayer(uuid);
                            if (p != null)
                            {
                                p.sendMessage(Component.text(
                                    "WorldEdit container limit reached (" + cap + "). Operation halted.",
                                    NamedTextColor.RED));
                            }
                        });
                    }
                    throw new MaxChangedBlocksException(cap);
                }
            }
            return super.setBlock(pos, block);
        }
    }

    private final class TileLimitExtent extends PathCompleteExtent
    {

        private final UUID uuid;
        private final int cap;
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicBoolean warned = new AtomicBoolean();

        TileLimitExtent(Extent parent, UUID uuid, int cap)
        {
            super(parent);
            this.uuid = uuid;
            this.cap = cap;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            if (hasBlockEntity(block) && count.incrementAndGet() > cap)
            {
                if (warned.compareAndSet(false, true))
                {
                    Bukkit.getScheduler().runTask(plugin, () ->
                    {
                        final Player p = Bukkit.getPlayer(uuid);
                        if (p != null)
                        {
                            p.sendMessage(Component.text(
                                "WorldEdit block-entity limit reached (" + cap + "). Operation halted.",
                                NamedTextColor.RED));
                        }
                    });
                }
                throw new MaxChangedBlocksException(cap);
            }
            return super.setBlock(pos, block);
        }
    }

    private final class BlockedTypeExtent extends PathCompleteExtent
    {

        private final UUID uuid;
        private final List<String> blockedLower;
        private final AtomicBoolean warned = new AtomicBoolean();

        BlockedTypeExtent(Extent parent, UUID uuid, List<String> blockedLower)
        {
            super(parent);
            this.uuid = uuid;
            this.blockedLower = blockedLower;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            if (isBlockedType(block, blockedLower))
            {
                if (warned.compareAndSet(false, true))
                {
                    Bukkit.getScheduler().runTask(plugin, () ->
                    {
                        final Player p = Bukkit.getPlayer(uuid);
                        if (p != null)
                        {
                            p.sendMessage(Component.text(
                                "One or more block types in your operation are not allowed and were skipped.",
                                NamedTextColor.RED));
                        }
                    });
                }
                return false;
            }
            return super.setBlock(pos, block);
        }
    }

    private final class SweepRegionExtent extends PathCompleteExtent
    {

        private final com.sk89q.worldedit.world.World weWorld;
        private final Set<Long> chunks = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        SweepRegionExtent(Extent parent, com.sk89q.worldedit.world.World weWorld)
        {
            super(parent);
            this.weWorld = weWorld;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 pos, T block)
            throws WorldEditException
        {
            final boolean placed = super.setBlock(pos, block);
            if (placed && isContainerBlock(block) && chunks.size() < MAX_SWEEP_TRACKED_CHUNKS)
            {
                chunks.add(chunkKey(pos.x() >> 4, pos.z() >> 4));
                if (flushScheduled.compareAndSet(false, true))
                {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            FTask.guard("WorldEditHook/sweepFlush", this::flush), SWEEP_FLUSH_DELAY_TICKS);
                }
            }
            return placed;
        }

        private void flush()
        {
            flushScheduled.set(false);
            final World world = Bukkit.getWorld(weWorld.getName());
            final java.util.Iterator<Long> it = chunks.iterator();
            while (it.hasNext())
            {
                final long key = it.next();
                it.remove();
                if (world == null)
                {
                    continue;
                }
                final int cx = (int) (key >> 32);
                final int cz = (int) key;
                if (world.isChunkLoaded(cx, cz))
                {
                    plugin.sweepScheduler.enqueueChunk(world.getChunkAt(cx, cz), SweepContext.EDIT);
                }
            }
        }
    }

    private static boolean isContainerBlock(BlockStateHolder<?> block)
    {
        final BlockType type = block.getBlockType();
        if (type == null)
        {
            return false;
        }
        final BlockMaterial mat = type.getMaterial();
        return mat != null && mat.hasContainer();
    }

    private static boolean hasBlockEntity(BlockStateHolder<?> block)
    {
        final BlockType type = block.getBlockType();
        if (type == null)
        {
            return false;
        }
        final BlockMaterial mat = type.getMaterial();
        if (mat != null && mat.hasContainer())
        {
            return true;
        }
        String id = type.id().toLowerCase(Locale.ROOT);
        final int colon = id.indexOf(':');
        if (colon >= 0)
        {
            id = id.substring(colon + 1);
        }
        if (BLOCK_ENTITY_IDS.contains(id))
        {
            return true;
        }
        for (String suffix : BLOCK_ENTITY_SUFFIXES)
        {
            if (id.endsWith(suffix))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedType(BlockStateHolder<?> block, List<String> blockedLower)
    {
        final BlockType type = block.getBlockType();
        if (type == null)
        {
            return false;
        }
        String id = type.id().toLowerCase(Locale.ROOT);
        final int colon = id.indexOf(':');
        if (colon >= 0)
        {
            id = id.substring(colon + 1);
        }
        for (String entry : blockedLower)
        {
            if (blockedIdMatches(id, entry))
            {
                return true;
            }
        }
        return false;
    }

    private static long chunkKey(int cx, int cz)
    {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static final class CountingLimitStream extends OutputStream
    {

        static final class LimitExceeded extends IOException
        {
        }

        private final long limit;
        private long count;

        CountingLimitStream(long limit)
        {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException
        {
            count++;
            if (count > limit)
            {
                throw new LimitExceeded();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            if (len <= 0)
            {
                return;
            }
            if (count + len > limit)
            {
                throw new LimitExceeded();
            }
            count += len;
        }
    }

    private static final class OpThrottle
    {
        long windowStart;
        int count;
        long lastViolation;
        int violations;
        long lastWarn;
    }

    private static final class RegionSnapshot
    {
        final BlockVector3 min;
        final BlockVector3 max;

        RegionSnapshot(BlockVector3 min, BlockVector3 max)
        {
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof RegionSnapshot))
            {
                return false;
            }
            RegionSnapshot r = (RegionSnapshot) o;
            return min.equals(r.min) && max.equals(r.max);
        }

        @Override
        public int hashCode()
        {
            return min.hashCode() * 31 + max.hashCode();
        }
    }
}
