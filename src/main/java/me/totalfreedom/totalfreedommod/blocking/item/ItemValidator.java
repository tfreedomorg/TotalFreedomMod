package me.totalfreedom.totalfreedommod.blocking.item;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.block.BlockPreDispenseEvent;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.*;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.EntityVisitor;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.blocking.sweep.TileEntityVisitor;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class ItemValidator extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final int MAX_COMMAND_LENGTH = 16384;
    private static final int MAX_COMMAND_BRACE_DEPTH = 8;
    private static final int DEFAULT_MAX_POTION_EFFECTS = 24;
    private static final long CLICK_SCAN_BUDGET_NANOS = 3_000_000L;
    private static final long CHUNK_ITEM_SCAN_BUDGET_NANOS = 2_000_000L;
    private static final long SWEEP_ITEM_SCAN_BUDGET_NANOS = 2_000_000L;
    private static final long CONTAINER_SCAN_BUDGET_NANOS = 10_000_000L;

    private volatile boolean panicMode;
    private volatile int maxPotionEffects = DEFAULT_MAX_POTION_EFFECTS;
    private volatile ContainerSweepPolicy containerSweepPolicy = ContainerSweepPolicy.CLEAR;

    private int sweepTaskId = -1;
    private int containerRadiusSweepTaskId = -1;

    private final TileEntityVisitor containerVisitor = new TileEntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return ItemValidator.this.enabled() && chunkLoadScanEnabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return 0L;
        }

        @Override
        public Predicate<Block> blockFilter()
        {
            return b -> true;
        }

        @Override
        public void visit(BlockState state, SweepContext context)
        {
            if (state instanceof InventoryHolder holder)
            {
                sanitizeInventoryHolder(holder, context.label());
            }
        }
    };

    private final EntityVisitor containerEntityVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return ItemValidator.this.enabled() && chunkLoadScanEnabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return 0L;
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            if (entity instanceof InventoryHolder holder)
            {
                sanitizeInventoryHolder(holder, context.label());
            }
        }
    };

    private final EntityVisitor equipmentEntityVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return ItemValidator.this.enabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return 0L;
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            // Players are covered by sweepPlayer, which also handles their carried inventory.
            if (entity instanceof Player || !(entity instanceof LivingEntity living))
            {
                return;
            }
            sweepEntityEquipment(living, context.label());
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[ItemValidator] Blocked " + count
                    + " cursed item(s). Reason: " + reason
                    + " | max observed size: " + max
                    + " | sample: " + sample,
            DetectionReporter.warnAndBroadcastAdmins(plugin));

    private final DetectionReporter ownerStripReporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[ItemValidator] Stripped spoofed entity_data owner from "
                    + count + " item(s). Sample: " + sample,
            DetectionReporter.warnAndBroadcastAdmins(plugin));

    public ItemValidator(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        panicMode = Boolean.TRUE.equals(ConfigEntry.CRASH_ITEMS_PANIC_MODE.getBoolean());
        Integer cap = ConfigEntry.CRASH_ITEMS_MAX_POTION_EFFECTS.getInteger();
        maxPotionEffects = cap != null ? cap : DEFAULT_MAX_POTION_EFFECTS;
        containerSweepPolicy = ContainerSweepPolicy.fromConfig(
                ConfigEntry.CRASH_CONTAINERS_SWEEP_MODE.getString());
        if (!RawNbtInspector.isAvailable())
        {
            FLog.warning("[ItemValidator] Raw NBT inspection is unavailable on this server runtime.");
        }
        scheduleEquipmentSweep();
        scheduleContainerRadiusSweep();
        plugin.sweepScheduler.register(containerVisitor);
        plugin.sweepScheduler.register(containerEntityVisitor);
        plugin.sweepScheduler.register(equipmentEntityVisitor);
    }

    @Override
    protected void onStop()
    {
        if (sweepTaskId != -1)
        {
            server.getScheduler().cancelTask(sweepTaskId);
            sweepTaskId = -1;
        }
        if (containerRadiusSweepTaskId != -1)
        {
            server.getScheduler().cancelTask(containerRadiusSweepTaskId);
            containerRadiusSweepTaskId = -1;
        }
    }

    /**
     * Periodically strips unwanted items from every online player's inventory,
     * armor, off-hand, and cursor.
     */
    private void scheduleEquipmentSweep()
    {
        long interval = ConfigEntry.CRASH_ITEMS_EQUIPMENT_SWEEP_TICKS.getInteger();
        if (interval <= 0)
        {
            return;
        }
        sweepTaskId = server.getScheduler().runTaskTimer(plugin,
                FTask.guard("ItemValidator/equipmentSweep", this::sweepOnlinePlayers), interval, interval).getTaskId();
    }

    private void scheduleContainerRadiusSweep()
    {
        if (!enabled() || !chunkLoadScanEnabled())
        {
            return;
        }
        long ticks = ConfigEntry.CRASH_CONTAINERS_SWEEP_TICKS.getInteger();
        if (ticks <= 0)
        {
            return;
        }
        containerRadiusSweepTaskId = server.getScheduler()
                .runTaskTimer(plugin, FTask.guard("ItemValidator/containerSweep", this::sweepContainersAroundPlayers), ticks, ticks)
                .getTaskId();
    }

    private void sweepContainersAroundPlayers()
    {
        if (!enabled() || !chunkLoadScanEnabled())
        {
            return;
        }
        int radius = Math.max(0, ConfigEntry.CRASH_CONTAINERS_SWEEP_RADIUS.getInteger());
        Set<String> visited = new HashSet<>();
        int acted = 0;
        for (Player player : server.getOnlinePlayers())
        {
            World world = player.getWorld();
            Chunk center = player.getLocation().getChunk();
            int baseX = center.getX();
            int baseZ = center.getZ();
            for (int dx = -radius; dx <= radius; dx++)
            {
                for (int dz = -radius; dz <= radius; dz++)
                {
                    int cx = baseX + dx;
                    int cz = baseZ + dz;
                    if (!world.isChunkLoaded(cx, cz))
                    {
                        continue;
                    }
                    if (!visited.add(world.getUID() + ":" + cx + ":" + cz))
                    {
                        continue;
                    }
                    acted += sweepContainerChunk(world.getChunkAt(cx, cz), "radius sweep");
                }
            }
        }
        if (acted > 0)
        {
            FLog.warning("[ItemValidator] Radius sweep handled " + acted + " cursed container(s).", true);
        }
    }

    private int sweepContainerChunk(Chunk chunk, String context)
    {
        Collection<BlockState> tiles;
        try
        {
            tiles = chunk.getTileEntities(b -> true, false);
        }
        catch (Throwable ignored)
        {
            return 0;
        }
        int acted = 0;
        for (BlockState state : tiles)
        {
            if (state instanceof InventoryHolder holder)
            {
                if (sanitizeInventoryHolder(holder, context))
                {
                    acted++;
                }
            }
        }
        for (Entity entity : chunk.getEntities())
        {
            if (entity instanceof InventoryHolder holder)
            {
                if (sanitizeInventoryHolder(holder, context))
                {
                    acted++;
                }
            }
        }
        return acted;
    }

    private void sweepOnlinePlayers()
    {
        if (!enabled())
        {
            return;
        }
        for (Player player : server.getOnlinePlayers())
        {
            sweepPlayer(player);
        }
        sweepGroundItems();
    }

    private void sweepEntityEquipment(LivingEntity living, String context)
    {
        final EntityEquipment equipment = living.getEquipment();
        if (equipment == null)
        {
            return;
        }

        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            final ItemStack item;
            try
            {
                item = equipment.getItem(slot);
            }
            catch (Throwable ignored)
            {
                continue;
            }

            if (item == null || item.isEmpty())
            {
                continue;
            }

            final ItemScanner.Verdict verdict = scanWithBudget(item, SWEEP_ITEM_SCAN_BUDGET_NANOS);
            if (!verdict.isCursed())
            {
                continue;
            }

            try
            {
                equipment.setItem(slot, null);
            }
            catch (Throwable ignored)
            {
                continue;
            }

            recordDetection(verdict, context + " equipment on " + living.getType() + " @ "
                    + FUtil.formatLocation(living.getLocation()));
        }
    }

    private void sweepGroundItems()
    {
        for (World world : server.getWorlds())
        {
            for (Item item : world.getEntitiesByClass(Item.class))
            {
                ItemScanner.Verdict v = scanWithBudget(item.getItemStack(), SWEEP_ITEM_SCAN_BUDGET_NANOS);
                if (v.isCursed())
                {
                    item.remove();
                    recordDetection(v, "ground sweep @ " + FUtil.formatLocation(item.getLocation()));
                }
            }
        }
    }

    private void sweepPlayer(Player player)
    {
        boolean dirty = false;

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++)
        {
            ItemStack item = contents[i];
            if (item == null)
            {
                continue;
            }
            ItemStack stripped = stripOwner(item, "equipment sweep on " + player.getName());
            if (stripped != null)
            {
                item = stripped;
                contents[i] = stripped;
                dirty = true;
            }
            ItemScanner.Verdict v = scanWithBudget(item, SWEEP_ITEM_SCAN_BUDGET_NANOS);
            if (v.isCursed())
            {
                contents[i] = sanitizeCursed(item, v, "equipment sweep on " + player.getName());
                dirty = true;
            }
        }
        if (dirty)
        {
            player.getInventory().setContents(contents);
        }

        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir())
        {
            ItemStack strippedCursor = stripOwner(cursor, "cursor sweep on " + player.getName());
            if (strippedCursor != null)
            {
                cursor = strippedCursor;
                player.setItemOnCursor(strippedCursor);
                dirty = true;
            }
            ItemScanner.Verdict v = scanWithBudget(cursor, SWEEP_ITEM_SCAN_BUDGET_NANOS);
            if (v.isCursed())
            {
                player.setItemOnCursor(sanitizeCursed(cursor, v, "cursor sweep on " + player.getName()));
                dirty = true;
            }
        }

        if (dirty)
        {
            player.updateInventory();
        }
    }

    public boolean isCursed(ItemStack item)
    {
        return scan(item).isCursed();
    }

    public int getMaxPotionEffects()
    {
        return maxPotionEffects;
    }

    private boolean enabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ITEMS_PREVENT.getBoolean());
    }

    private boolean chunkLoadScanEnabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_CONTAINERS_SCAN_CHUNK_LOAD.getBoolean());
    }

    private ItemScanner.Verdict scan(ItemStack item)
    {
        return ItemScanner.scan(item, panicMode, maxPotionEffects);
    }

    private ItemScanner.Verdict scanWithBudget(ItemStack item, long budgetNanos)
    {
        if (item == null || item.isEmpty() || budgetNanos <= 0L)
        {
            return scan(item);
        }
        return ItemScanner.scan(item, panicMode, maxPotionEffects, System.nanoTime() + budgetNanos);
    }

    private ItemScanner.Verdict scanUntil(ItemStack item, long deadlineNanos)
    {
        if (item == null || item.isEmpty())
        {
            return scan(item);
        }
        return ItemScanner.scan(item, panicMode, maxPotionEffects, deadlineNanos);
    }

    private ItemScanner.Verdict scanInventory(Inventory inv)
    {
        return scanInventory(inv, CHUNK_ITEM_SCAN_BUDGET_NANOS);
    }

    private ItemScanner.Verdict scanInventory(Inventory inv, long perItemBudgetNanos)
    {
        if (inv == null)
        {
            return ItemScanner.Verdict.CLEAN;
        }
        for (ItemStack item : inv.getContents())
        {
            ItemScanner.Verdict v = scanWithBudget(item, perItemBudgetNanos);
            if (v.isCursed())
            {
                return v;
            }
        }
        return ItemScanner.Verdict.CLEAN;
    }

    /**
     * Budgeted first-hit scan of an open world container during click/drag/open.
     * Escalates hang-class hits (or unreadable inventories) per {@link ContainerSweepPolicy}.
     * Escalation resolves the backing block or mobile entity via inventory location /
     * {@link #resolveEntityHolder(Inventory)} instead of {@link Inventory#getHolder()},
     * which re-deserializes block-entity NBT and can hang the server on exploit chests.
     *
     * @return {@code true} when the interaction should be cancelled (container handled)
     */
    private boolean probeOpenContainer(Inventory top, String context)
    {
        ItemScanner.Verdict v = scanContainerFirstHit(top);
        if (!v.isCursed())
        {
            return false;
        }
        ContainerSweepPolicy.Action action = containerSweepPolicy.actionFor(v.reason());
        if (action == ContainerSweepPolicy.Action.FILTER_SLOT)
        {
            return false;
        }
        return escalateResolvedContainer(top, v, action, context);
    }

    private ItemScanner.Verdict scanContainerFirstHit(Inventory top)
    {
        if (top == null)
        {
            return ItemScanner.Verdict.CLEAN;
        }
        ItemStack[] contents;
        try
        {
            contents = top.getContents();
        }
        catch (Throwable t)
        {
            return uninspectableVerdict();
        }
        long containerDeadline = System.nanoTime() + CONTAINER_SCAN_BUDGET_NANOS;
        for (ItemStack item : contents)
        {
            if (item == null)
            {
                continue;
            }
            long itemDeadline = Math.min(System.nanoTime() + CHUNK_ITEM_SCAN_BUDGET_NANOS, containerDeadline);
            ItemScanner.Verdict v = scanUntil(item, itemDeadline);
            if (v.isCursed())
            {
                return v;
            }
        }
        return ItemScanner.Verdict.CLEAN;
    }

    private boolean escalateResolvedContainer(Inventory top, ItemScanner.Verdict verdict,
            ContainerSweepPolicy.Action action, String context)
    {
        // Must precede every world resolution below. A player-backed view reports the
        // viewer's own position from Inventory#getLocation(), so resolving it to a block
        // targets whatever the player happens to be standing in. The payload still has to
        // come out, so it is stripped from the view itself instead.
        if (isPlayerBackedInventory(top))
        {
            return clearPlayerCraftingGrid(top, context, verdict);
        }

        final Optional<InventoryHolder> holder = resolveEntityHolder(top);
        if (holder.isPresent())
        {
            return escalateHolder(holder.get(), top, action, verdict, context);
        }
        final Optional<Block> block = blockFromInventoryLocation(top);
        if (block.isPresent())
        {
            return escalateBlockContainer(block.get(), top, action, verdict, context);
        }
        if (action == ContainerSweepPolicy.Action.CLEAR_CONTAINER)
        {
            top.clear();
            recordDetection(verdict, context + " [container cleared]");
            return true;
        }
        return false;
    }

    private boolean sanitizeInventoryHolder(InventoryHolder holder, String context)
    {
        if (!isWorldContainerHolder(holder))
        {
            return false;
        }
        Inventory inv;
        try
        {
            inv = holder.getInventory();
        }
        catch (Throwable t)
        {
            ItemScanner.Verdict v = uninspectableVerdict();
            return escalateHolder(holder, null, containerSweepPolicy.actionFor(v.reason()), v,
                    context + " @ " + describeHolder(holder));
        }
        return sanitizeContainerInventory(inv, holder, context);
    }

    /**
     * Filters a container's slots in place, escalating on the first hang-class hit.
     *
     * <p>{@code holder} may be {@code null} for a view opened by a player. Resolving the
     * holder there would mean calling {@link Inventory#getHolder()}, which re-deserializes
     * the very block-entity NBT this class exists to defend against; the backing container
     * is resolved from the inventory's location instead.
     */
    private boolean sanitizeContainerInventory(Inventory inv, InventoryHolder holder, String context)
    {
        if (inv == null || (holder != null && !isWorldContainerHolder(holder)))
        {
            return false;
        }
        String sample = context + " @ "
                + (holder != null ? describeHolder(holder) : describeLocation(inv));

        ItemStack[] contents;
        try
        {
            contents = inv.getContents();
        }
        catch (Throwable t)
        {
            // A null holder would fall through destroyHolder without acting, so an
            // unreadable player-opened container has to resolve its own backing block.
            ItemScanner.Verdict v = uninspectableVerdict();
            ContainerSweepPolicy.Action action = containerSweepPolicy.actionFor(v.reason());
            return holder != null
                    ? escalateHolder(holder, null, action, v, sample)
                    : escalateResolvedContainer(inv, v, action, sample);
        }

        long containerDeadline = System.nanoTime() + CONTAINER_SCAN_BUDGET_NANOS;
        boolean purged = false;
        for (int i = 0; i < contents.length; i++)
        {
            ItemStack item = contents[i];
            if (item == null)
            {
                continue;
            }
            ItemStack stripped = stripOwner(item, sample);
            if (stripped != null)
            {
                item = stripped;
                contents[i] = stripped;
                purged = true;
            }
            long itemDeadline = Math.min(System.nanoTime() + CHUNK_ITEM_SCAN_BUDGET_NANOS, containerDeadline);
            ItemScanner.Verdict v = scanUntil(item, itemDeadline);
            if (!v.isCursed())
            {
                continue;
            }
            ContainerSweepPolicy.Action action = containerSweepPolicy.actionFor(v.reason());
            if (action != ContainerSweepPolicy.Action.FILTER_SLOT)
            {
                return holder != null
                        ? escalateHolder(holder, inv, action, v, sample)
                        : escalateResolvedContainer(inv, v, action, sample);
            }
            contents[i] = null;
            purged = true;
            recordDetection(v, sample);
        }
        if (purged)
        {
            inv.setContents(contents);
        }
        return purged;
    }

    /**
     * Applies a container-wide sweep action for a hang-class verdict. Empties the
     * container when asked to clear, removes the backing holder when asked to
     * destroy, and falls back to removal when the inventory cannot be read.
     */
    private boolean escalateHolder(InventoryHolder holder, Inventory inv, ContainerSweepPolicy.Action action,
            ItemScanner.Verdict verdict, String sample)
    {
        if (action == ContainerSweepPolicy.Action.FILTER_SLOT)
        {
            return false;
        }
        if (action == ContainerSweepPolicy.Action.CLEAR_CONTAINER && inv != null)
        {
            inv.clear();
            recordDetection(verdict, sample + " [container cleared]");
            return true;
        }
        destroyHolder(holder, sample, verdict);
        return true;
    }

    /**
     * Block-only escalation for click paths that avoid {@link Inventory#getHolder()}.
     */
    private boolean escalateBlockContainer(Block block, Inventory inv, ContainerSweepPolicy.Action action,
            ItemScanner.Verdict verdict, String sample)
    {
        if (action == ContainerSweepPolicy.Action.FILTER_SLOT)
        {
            return false;
        }
        if (action == ContainerSweepPolicy.Action.CLEAR_CONTAINER && inv != null)
        {
            inv.clear();
            recordDetection(verdict, sample + " [container cleared]");
            return true;
        }
        destroyContainerBlock(block, sample, verdict);
        return true;
    }

    private static ItemScanner.Verdict uninspectableVerdict()
    {
        return new ItemScanner.Verdict(ItemScanner.Reason.UNINSPECTABLE_NBT, -1L, 0);
    }

    /**
     * Strips the cursed slots out of a player-backed view (the 2x2 crafting grid and its
     * result slot, or the player inventory) rather than escalating to the world. A creative
     * client can write a cursed stack straight into those slots, and every subsequent click
     * re-reports it, so the stack has to be physically removed or the source keeps firing.
     * Clearing happens before the caller closes the view, otherwise the grid contents would
     * be dropped into the world as item entities on close.
     *
     * @return always {@code true}; the verdict was already cursed, so the interaction is
     *         handled even when the offending stack lost a race and is no longer present
     */
    private boolean clearPlayerCraftingGrid(final Inventory view, final String context,
            final ItemScanner.Verdict verdict)
    {
        if (!cleanInventory(view, CHUNK_ITEM_SCAN_BUDGET_NANOS, context + " [player view purged]"))
        {
            recordDetection(verdict, context + " [player view already clean]");
        }

        view.getViewers()
            .stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .forEach(Player::updateInventory);

        return true;
    }

    private void destroyContainerBlock(Block block, String context, ItemScanner.Verdict verdict)
    {
        // Fail closed on anything that is not a live container. Destroying air succeeds
        // silently and still logs, which is what turned a single bad resolution into a
        // warning per event for as long as the client kept firing.
        if (!isContainerBlock(block))
            return;

        try
        {
            block.setType(Material.AIR, false);
        }
        catch (Throwable th) // because we ignored this, we don't see why the itemvalidator spams the logs.
        {
            FLog.severe(ExceptionUtils.getRootCauseMessage(th));
            return; // Returning here so it doesn't enter a recursive loop of spamming logs with nonsense. Instead, we will spam the logs with actual errors.
        }
        recordDetection(verdict, context + " [block destroyed]");
        FLog.warning("[ItemValidator] Destroyed container block at " + FUtil.formatLocation(block.getLocation())
                + " (" + verdict.reason() + ").", true);
    }

    private void destroyHolder(InventoryHolder holder, String context, ItemScanner.Verdict verdict)
    {
        if (holder instanceof BlockState state)
        {
            destroyContainerBlock(state.getBlock(), context, verdict);
            return;
        }
        if (holder instanceof Entity entity)
        {
            try
            {
                entity.remove();
            }
            catch (Throwable ignored)
            {
            }
            recordDetection(verdict, context + " [entity removed]");
            FLog.warning("[ItemValidator] Removed container entity at "
                    + FUtil.formatLocation(entity.getLocation())
                    + " (" + verdict.reason() + ").", true);
        }
    }

    private static boolean isWorldContainerHolder(InventoryHolder holder)
    {
        return holder != null && !(holder instanceof Player);
    }

    /**
     * Confirms a resolved block still carries a container block entity before it is
     * destroyed. Reads the live state rather than a snapshot, since snapshotting
     * re-deserializes the block-entity NBT this class exists to defend against.
     */
    private static boolean isContainerBlock(Block block)
    {
        if (block == null || block.getType().isAir())
        {
            return false;
        }

        try
        {
            return block.getState(false) instanceof InventoryHolder;
        }
        catch (Throwable ignored) {}

        return false;
    }

    /**
     * Identifies views that have no backing container block, so escalation must never
     * resolve them to a location. CRAFTING (the player's own 2x2 grid) and PLAYER both
     * report the viewer's position, which is why destroying "their" block hit whatever
     * the player was standing in. WORKBENCH does map to a real block, but a crafting
     * table holds no inventory and must not be destroyed either.
     *
     * <p>Deliberately keyed on {@link Inventory#getType()} rather than
     * {@link Inventory#getHolder()}: the holder lookup re-deserializes block-entity NBT
     * and can hang the server on an exploit chest, which is the hazard this whole class
     * is built to avoid.
     */
    private static boolean isPlayerBackedInventory(Inventory inv)
    {
        if (inv == null)
        {
            return false;
        }

        final InventoryType type = inv.getType();
        return type == InventoryType.CRAFTING
                || type == InventoryType.PLAYER
                || type == InventoryType.WORKBENCH;
    }

    /**
     * Decides whether an opened view is a world container that escalation may act on,
     * without consulting {@link Inventory#getHolder()}. That call re-deserializes
     * block-entity NBT and can hang the server on exactly the exploit chest this handler
     * is meant to intercept.
     *
     * <p>A view qualifies only when its location resolves to a live container block or to
     * a nearby non-player entity holder. Testing the resolution instead of enumerating
     * inventory types is what keeps player-anchored views out: an ender chest, a villager
     * trade window and the creative menu all report a position, but none of them resolve
     * to a container, so none of them can be escalated against the world.
     */
    private static boolean isWorldContainerView(Inventory inv)
    {
        if (inv == null || isPlayerBackedInventory(inv))
        {
            return false;
        }

        final Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null)
        {
            return false;
        }

        return isContainerBlock(loc.getBlock()) || resolveEntityHolder(inv).isPresent();
    }

    private static String describeHolder(InventoryHolder holder)
    {
        if (holder instanceof BlockState state)
        {
            return FUtil.formatLocation(state.getLocation());
        }
        if (holder instanceof Entity entity)
        {
            return FUtil.formatLocation(entity.getLocation());
        }
        Inventory inv = holder.getInventory();
        if (inv != null && inv.getLocation() != null)
        {
            return FUtil.formatLocation(inv.getLocation());
        }
        return "unknown";
    }

    /**
     * Locates a mobile {@link InventoryHolder} (minecart, chested horse, ...) for
     * an open inventory without calling {@link Inventory#getHolder()}, which would
     * re-load block-entity NBT for placed chests.
     */
    private static Optional<InventoryHolder> resolveEntityHolder(Inventory inv)
    {
        if (inv == null)
        {
            return Optional.empty();
        }

        final Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null)
        {
            return Optional.empty();
        }

        try
        {
            // Players are excluded deliberately. A player standing inside the search box
            // would otherwise be escalated as the container holder and removed outright.
            return loc.getWorld()
                      .getNearbyEntities(loc, 1.0, 1.0, 1.0)
                      .stream()
                      .filter(InventoryHolder.class::isInstance)
                      .map(InventoryHolder.class::cast)
                      .filter(holder -> !(holder instanceof Player))
                      .filter(holder -> holder.getInventory() == inv)
                      .findFirst();
        }
        catch (Throwable ignored) {}

        return Optional.empty();
    }

    private void recordDetection(ItemScanner.Verdict v, String context)
    {
        reporter.record(v.reason().name(), v.observedSize(), context);
    }

    // ===== Command-side gates =====

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        // Swept at join (not login): the player's inventory is reliably loaded
        // here, and setContents/updateInventory affect a fully-online player.
        if (!enabled())
        {
            return;
        }
        sweepPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Player player = event.getPlayer();
        if (isCursedCommand(event.getMessage()))
        {
            event.setCancelled(true);
            FUtil.playerMsg(player, "Your command was rejected: it would create a cursed item.", NamedTextColor.RED);
            recordDetection(
                    new ItemScanner.Verdict(ItemScanner.Reason.OVERSIZED_TOTAL, event.getMessage().length(), 0),
                    "command by " + player.getName());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event)
    {
        if (!enabled())
        {
            return;
        }
        if (isCursedCommand(event.getCommand()))
        {
            event.setCancelled(true);
            recordDetection(
                    new ItemScanner.Verdict(ItemScanner.Reason.OVERSIZED_TOTAL, event.getCommand().length(), 0),
                    "console: " + event.getSender().getName());
        }
    }

    private boolean isCursedCommand(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return false;
        }

        String command = raw.trim();
        if (command.startsWith("/"))
        {
            command = command.substring(1);
        }
        int sp = command.indexOf(' ');
        String head = (sp < 0 ? command : command.substring(0, sp)).toLowerCase();

        @SuppressWarnings("unchecked")
        List<String> inspected = (List<String>) ConfigEntry.CRASH_ITEMS_BASE_COMMANDS.getList();
        if (inspected.isEmpty())
        {
            return false;
        }

        boolean match = false;
        for (String name : inspected)
        {
            if (name == null || name.isEmpty())
            {
                continue;
            }
            String n = name.toLowerCase();
            if (head.equals(n) || head.equals("minecraft:" + n))
            {
                match = true;
                break;
            }
        }
        if (!match)
        {
            return false;
        }

        if (raw.length() > MAX_COMMAND_LENGTH)
        {
            return true;
        }

        int depth = 0;
        int peak = 0;
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            if (c == '{' || c == '[')
            {
                depth++;
                if (depth > peak)
                {
                    peak = depth;
                }
            }
            else if (c == '}' || c == ']')
            {
                depth--;
            }
        }
        return peak > MAX_COMMAND_BRACE_DEPTH;
    }

    // ===== Container choke points (LOWEST: act before CoreProtect / WorldGuard, which load AFTER TFM) =====

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Inventory top = event.getInventory();
        if (isWorldContainerView(top))
        {
            String context = event.getPlayer().getName() + " opened " + describeLocation(top);
            if (probeOpenContainer(top, context))
            {
                event.setCancelled(true);
            }
            else
            {
                ItemScanner.Verdict v = scanContainerFirstHit(top);
                if (!v.isCursed())
                {
                    return;
                }
                event.setCancelled(true);
                recordDetection(v, context);
                sanitizeContainerInventory(top, null, context);
            }
            if (event.getPlayer() instanceof Player p)
            {
                FUtil.playerMsg(p,
                        "That container holds a cursed item.",
                        NamedTextColor.RED);
            }
            return;
        }

        ItemScanner.Verdict v = scanInventory(top);
        if (!v.isCursed())
        {
            return;
        }

        event.setCancelled(true);
        recordDetection(v, event.getPlayer().getName() + " opened " + describeLocation(top));

        if (event.getPlayer() instanceof Player p)
        {
            FUtil.playerMsg(p,
                    "That container holds a cursed item.",
                    NamedTextColor.RED);
        }
        cleanInventory(top);
    }

    private boolean cleanInventory(Inventory inv)
    {
        return cleanInventory(inv, 0L, null);
    }

    private boolean cleanInventory(Inventory inv, long perItemBudgetNanos, String context)
    {
        ItemStack[] contents = inv.getContents();
        boolean dirty = false;
        for (int i = 0; i < contents.length; i++)
        {
            ItemStack item = contents[i];
            if (item == null)
            {
                continue;
            }
            ItemScanner.Verdict v = scanWithBudget(item, perItemBudgetNanos);
            if (v.isCursed())
            {
                contents[i] = null;
                dirty = true;
                if (context != null)
                {
                    recordDetection(v, context);
                }
            }
        }
        if (dirty)
        {
            inv.setContents(contents);
        }
        return dirty;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event)
    {
        if (!enabled())
        {
            return;
        }
        String actor = event.getWhoClicked().getName();
        Inventory top = event.getView().getTopInventory();
        if (probeOpenContainer(top, "click by " + actor))
        {
            event.setCancelled(true);
            closeContainerView(event.getWhoClicked(), top);
            return;
        }

        long deadline = System.nanoTime() + CLICK_SCAN_BUDGET_NANOS;
        ItemScanner.Verdict cur = ItemScanner.scan(event.getCurrentItem(), panicMode, maxPotionEffects, deadline);
        ItemScanner.Verdict csr = ItemScanner.scan(event.getCursor(), panicMode, maxPotionEffects, deadline);
        if (!cur.isCursed() && !csr.isCursed())
        {
            return;
        }

        event.setCancelled(true);
        ItemScanner.Verdict hang = hangClassVerdict(cur, csr);
        if (hang != null)
        {
            ContainerSweepPolicy.Action action = containerSweepPolicy.actionFor(hang.reason());
            if (action != ContainerSweepPolicy.Action.FILTER_SLOT)
            {
                escalateResolvedContainer(top, hang, action, "click by " + actor);
                closeContainerView(event.getWhoClicked(), top);
                return;
            }
        }

        if (cur.isCursed())
        {
            event.setCurrentItem(null);
            recordDetection(cur, "click slot by " + actor);
        }
        if (csr.isCursed())
        {
            event.getView().setCursor(null);
            recordDetection(csr, "click cursor by " + actor);
        }
        if (event.getWhoClicked() instanceof Player p)
        {
            p.updateInventory();
            FUtil.playerMsg(p, "A cursed item was removed.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryCreative(InventoryCreativeEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getCursor());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        event.getView().setCursor(null);
        recordDetection(v, "creative pickup by " + event.getWhoClicked().getName());
        if (event.getWhoClicked() instanceof Player p)
        {
            p.updateInventory();
            FUtil.playerMsg(p, "A cursed item was removed.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event)
    {
        if (!enabled())
        {
            return;
        }
        String actor = event.getWhoClicked().getName();
        Inventory top = event.getView().getTopInventory();
        if (probeOpenContainer(top, "drag by " + actor))
        {
            event.setCancelled(true);
            closeContainerView(event.getWhoClicked(), top);
            return;
        }

        ItemScanner.Verdict v = scan(event.getOldCursor());
        if (!v.isCursed())
        {
            for (ItemStack inner : event.getNewItems().values())
            {
                ItemScanner.Verdict iv = scan(inner);
                if (iv.isCursed())
                {
                    v = iv;
                    break;
                }
            }
        }
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        if (v.reason().isHangClass())
        {
            ContainerSweepPolicy.Action action = containerSweepPolicy.actionFor(v.reason());
            if (action != ContainerSweepPolicy.Action.FILTER_SLOT)
            {
                escalateResolvedContainer(top, v, action, "drag by " + actor);
                closeContainerView(event.getWhoClicked(), top);
                return;
            }
        }
        recordDetection(v, "drag by " + actor);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItem());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "hopper into " + describeLocation(event.getDestination()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryPickupItem(InventoryPickupItemEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItem().getItemStack());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        recordDetection(v, "hopper pickup at " + describeLocation(event.getInventory()));
    }

    // ===== Lower-priority gates =====

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItem().getItemStack());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        recordDetection(v, "pickup by " + event.getEntity().getName());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItemDrop().getItemStack());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "drop by " + event.getPlayer().getName());
        FUtil.playerMsg(event.getPlayer(), "You cannot drop a cursed item.", NamedTextColor.RED);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItem());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "consume by " + event.getPlayer().getName());
        FUtil.playerMsg(event.getPlayer(), "You cannot consume a cursed item.", NamedTextColor.RED);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getEntity().getItemStack());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "item spawn at " + FUtil.formatLocation(event.getLocation()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItemInHand());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "place by " + event.getPlayer().getName());
        FUtil.playerMsg(event.getPlayer(), "You cannot place a cursed container.", NamedTextColor.RED);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getItem());
        if (!v.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        recordDetection(v, "dispense at " + FUtil.formatLocation(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCursedEntityDataUse(PlayerInteractEvent event)
    {
        if (!enabled())
        {
            return;
        }
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
        {
            return;
        }
        final ItemStack item = event.getItem();
        // Only entity/bucket data can carry a spoofed entity id
        if (item == null || !RawNbtInspector.hasEntityOrBucketData(item))
        {
            return;
        }
        final ItemScanner.Verdict verdict = scanWithBudget(item, CLICK_SCAN_BUDGET_NANOS);
        if (!verdict.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        clearHand(event.getPlayer(), event.getHand());
        recordDetection(verdict, "use by " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCursedEntityDataUseOnEntity(PlayerInteractEntityEvent event)
    {
        if (!enabled())
        {
            return;
        }
        final Player player = event.getPlayer();
        final EquipmentSlot hand = event.getHand();
        final ItemStack item = hand == EquipmentSlot.OFF_HAND
                               ? player.getInventory().getItemInOffHand()
                               : player.getInventory().getItemInMainHand();
        if (item == null || !RawNbtInspector.hasEntityOrBucketData(item))
        {
            return;
        }
        final ItemScanner.Verdict verdict = scanWithBudget(item, CLICK_SCAN_BUDGET_NANOS);
        if (!verdict.isCursed())
        {
            return;
        }
        event.setCancelled(true);
        clearHand(player, hand);
        recordDetection(verdict, "use-on-entity by " + player.getName());
    }

    private void clearHand(Player player, EquipmentSlot hand)
    {
        if (hand == EquipmentSlot.OFF_HAND)
        {
            player.getInventory().setItemInOffHand(null);
        }
        else
        {
            player.getInventory().setItemInMainHand(null);
        }
        player.updateInventory();
        FUtil.playerMsg(player, "A cursed item was removed.", NamedTextColor.RED);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpoofedOwnerInteract(PlayerInteractEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
        {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !RawNbtInspector.hasEntityDataOwner(item))
        {
            return;
        }
        event.setCancelled(true);
        stripFromHand(event.getPlayer(), event.getHand(), item, "use by " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpoofedOwnerInteractEntity(PlayerInteractEntityEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item == null || !RawNbtInspector.hasEntityDataOwner(item))
        {
            return;
        }
        event.setCancelled(true);
        stripFromHand(player, hand, item, "use-on-entity by " + player.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpoofedOwnerDispense(BlockPreDispenseEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemStack item = event.getItemStack();
        if (item == null || !RawNbtInspector.hasEntityDataOwner(item))
        {
            return;
        }
        event.setCancelled(true);
        ownerStripReporter.record("SPOOFED_OWNER", 0L,
                "dispense at " + FUtil.formatLocation(event.getBlock().getLocation()));
    }

    private void stripFromHand(Player player, EquipmentSlot hand, ItemStack item, String context)
    {
        ItemStack cleaned = stripOwner(item, context);
        if (cleaned == null)
        {
            return;
        }
        if (hand == EquipmentSlot.OFF_HAND)
        {
            player.getInventory().setItemInOffHand(cleaned);
        }
        else
        {
            player.getInventory().setItemInMainHand(cleaned);
        }
        player.updateInventory();
        FUtil.playerMsg(player, "A spoofed owner tag was stripped from that item.", NamedTextColor.RED);
    }

    private ItemStack stripOwner(ItemStack item, String context)
    {
        if (item == null || !RawNbtInspector.hasEntityDataOwner(item))
        {
            return null;
        }
        ItemStack cleaned = RawNbtInspector.stripEntityData(item);
        if (cleaned == null || RawNbtInspector.hasEntityDataOwner(cleaned))
        {
            return null;
        }
        ownerStripReporter.record("SPOOFED_OWNER", 0L, context);
        return cleaned;
    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event)
    {
        if (!enabled())
        {
            return;
        }
        String who = event.getPlayer().getName();
        ItemStack toOff = event.getOffHandItem();
        ItemScanner.Verdict vOff = scanWithBudget(toOff, SWEEP_ITEM_SCAN_BUDGET_NANOS);
        if (vOff.isCursed())
        {
            event.setOffHandItem(sanitizeCursed(toOff, vOff, "off-hand swap by " + who));
        }
        ItemStack toMain = event.getMainHandItem();
        ItemScanner.Verdict vMain = scanWithBudget(toMain, SWEEP_ITEM_SCAN_BUDGET_NANOS);
        if (vMain.isCursed())
        {
            event.setMainHandItem(sanitizeCursed(toMain, vMain, "hand swap by " + who));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Player player = event.getPlayer();
        int slot = event.getNewSlot();
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || !item.hasData(DataComponentTypes.EQUIPPABLE))
        {
            return;
        }
        ItemScanner.Verdict v = scanWithBudget(item, SWEEP_ITEM_SCAN_BUDGET_NANOS);
        if (!v.isCursed())
        {
            return;
        }
        player.getInventory().setItem(slot, sanitizeCursed(item, v, "hotbar select by " + player.getName()));
        player.updateInventory();
    }

    private ItemStack sanitizeCursed(ItemStack item, ItemScanner.Verdict v, String context)
    {
        recordDetection(v, context);
        if (v.reason() == ItemScanner.Reason.CURSED_EQUIP_SOUND)
        {
            ItemStack neutralized = neutralizeEquipSound(item);
            if (neutralized != null)
            {
                return neutralized;
            }
        }
        return null;
    }

    private ItemStack neutralizeEquipSound(ItemStack item)
    {
        if (item == null || item.isEmpty())
        {
            return null;
        }
        try
        {
            ItemStack cleaned = item.clone();
            cleaned.resetData(DataComponentTypes.EQUIPPABLE);
            return scan(cleaned).reason() == ItemScanner.Reason.CURSED_EQUIP_SOUND ? null : cleaned;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareAnvil(PrepareAnvilEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemScanner.Verdict v = scan(event.getResult());
        if (!v.isCursed())
        {
            return;
        }
        event.setResult(null);
        recordDetection(v, "anvil result");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareItemCraft(PrepareItemCraftEvent event)
    {
        if (!enabled())
        {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        ItemScanner.Verdict v = scan(result);
        if (!v.isCursed())
        {
            return;
        }
        event.getInventory().setResult(null);
        recordDetection(v, "craft result");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event)
    {
        if (!enabled())
        {
            return;
        }
        List<ItemStack> loot = event.getLoot();
        boolean dirty = false;
        for (int i = loot.size() - 1; i >= 0; i--)
        {
            ItemScanner.Verdict v = scan(loot.get(i));
            if (v.isCursed())
            {
                loot.remove(i);
                recordDetection(v, "loot table");
                dirty = true;
            }
        }
        if (dirty)
        {
            event.setLoot(loot);
        }
    }

    private String describeLocation(Inventory inv)
    {
        if (inv == null || inv.getLocation() == null)
        {
            return "unknown";
        }
        return FUtil.formatLocation(inv.getLocation());
    }

    /**
     * Resolves the block backing an open container. Player-backed views are rejected by
     * {@link #isPlayerBackedInventory(Inventory)} before this is reached, so the holder is
     * deliberately never consulted here: {@link Inventory#getHolder()} re-deserializes
     * block-entity NBT and can hang the server on an exploit chest.
     */
    private static Optional<Block> blockFromInventoryLocation(Inventory inv)
    {
        if (inv == null)
        {
            return Optional.empty();
        }

        final Location loc = inv.getLocation();
        if (loc == null || loc.getWorld() == null)
        {
            return Optional.empty();
        }

        return Optional.of(loc.getBlock());
    }

    private static ItemScanner.Verdict hangClassVerdict(ItemScanner.Verdict a, ItemScanner.Verdict b)
    {
        if (a.isCursed() && a.reason().isHangClass())
        {
            return a;
        }
        if (b.isCursed() && b.reason().isHangClass())
        {
            return b;
        }
        return null;
    }

    private static void closeContainerView(HumanEntity viewer, Inventory top)
    {
        if (!(viewer instanceof Player p))
        {
            return;
        }

        p.closeInventory();

        // A player-backed view has no container to remove; only its cursed slots were
        // stripped, so reporting a removal would be misleading.
        FUtil.playerMsg(p, isPlayerBackedInventory(top)
                ? "A cursed item was removed."
                : "That container was removed because it contained invalid data.", NamedTextColor.GRAY);
    }
}
