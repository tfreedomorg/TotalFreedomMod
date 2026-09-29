package me.totalfreedom.totalfreedommod;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public class TextFilterService extends FreedomService
{
    private static final int ITEM_DATA_PREVIEW_LIMIT = 512;

    private static final Map<Character, Character> LEET = Map.ofEntries(
        Map.entry('0', 'O'), Map.entry('1', 'I'), Map.entry('3', 'E'),
        Map.entry('4', 'A'), Map.entry('5', 'S'), Map.entry('7', 'T'),
        Map.entry('!', 'I'), Map.entry('|', 'I'), Map.entry('+', 'T'));

    private List<Pattern> filters = List.of();
    private List<Pattern> usernameFilters = List.of();

    public TextFilterService(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        reloadFilters();
    }

    @Override
    protected void onStop()
    {
        filters = List.of();
        usernameFilters = List.of();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event)
    {
        if (!shouldFilter(event.getPlayer()))
        {
            return;
        }

        final String message = MessageUtils.toPlainText(event.message());
        if (!matchesFilter(message))
        {
            return;
        }

        event.setCancelled(true);
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> handleBlockedMessage(player, message));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (!shouldFilter(event.getPlayer()))
        {
            return;
        }

        if (!matchesFilter(event.getMessage()))
        {
            return;
        }

        event.setCancelled(true);
        handleBlockedMessage(event.getPlayer(), event.getMessage());
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerEditBook(PlayerEditBookEvent event)
    {
        if (!shouldFilter(event.getPlayer()))
        {
            return;
        }

        final BookMeta book = event.getNewBookMeta();
        final Optional<String> filteredText = findFilteredText(book.getTitle())
            .or(() -> findFilteredText(book.getAuthor()))
            .or(() -> book.getPages().stream()
                .map(MessageUtils::toPlainText)
                .filter(this::matchesFilter)
                .findFirst());
        if (filteredText.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        final int slot = event.getSlot();
        final ItemStack item = slot >= 0
            ? event.getPlayer().getInventory().getItem(slot)
            : event.getPlayer().getInventory().getItemInOffHand();
        notifyAdmins(event.getPlayer(), item, String.format("contains prohibited text: %s", filteredText.get()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event)
    {
        if (!(event.getWhoClicked() instanceof Player player) || !shouldFilter(player))
        {
            return;
        }

        final Optional<String> currentText = findFilteredItemText(event.getCurrentItem());
        final Optional<String> cursorText = findFilteredItemText(event.getCursor());
        if (currentText.isEmpty() && cursorText.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        final String filteredText = Stream.of(currentText, cursorText)
            .flatMap(Optional::stream)
            .distinct()
            .collect(Collectors.joining(", "));
        final ItemStack item = currentText.isPresent() ? event.getCurrentItem() : event.getCursor();
        notifyAdmins(player, item, String.format("contains prohibited text: %s", filteredText));
        if (currentText.isPresent())
        {
            event.setCurrentItem(null);
        }
        if (cursorText.isPresent())
        {
            event.getView().setCursor(null);
        }
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryCreative(InventoryCreativeEvent event)
    {
        if (!(event.getWhoClicked() instanceof Player player) || !shouldFilter(player))
        {
            return;
        }

        final Optional<String> filteredText = findFilteredItemText(event.getCursor());
        if (filteredText.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        notifyAdmins(player, event.getCursor(), String.format("contains prohibited text: %s", filteredText.get()));
        event.getView().setCursor(null);
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event)
    {
        if (!(event.getWhoClicked() instanceof Player player) || !shouldFilter(player))
        {
            return;
        }

        final Optional<Map.Entry<ItemStack, String>> match = Stream.concat(
                Stream.of(event.getOldCursor()), event.getNewItems().values().stream())
            .map(item -> findFilteredItemText(item).map(text -> Map.entry(item, text)))
            .flatMap(Optional::stream)
            .findFirst();
        if (match.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        notifyAdmins(player, match.get().getKey(),
            String.format("contains prohibited text: %s", match.get().getValue()));
        event.getView().setCursor(null);
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event)
    {
        if (!shouldFilter(event.getPlayer()))
        {
            return;
        }

        final Player player = event.getPlayer();
        final ItemStack item = player.getInventory().getItem(event.getNewSlot());
        final Optional<String> filteredText = findFilteredItemText(item);
        if (filteredText.isPresent())
        {
            notifyAdmins(player, item,
                String.format("contains prohibited text: %s", filteredText.get()));
            player.getInventory().setItem(event.getNewSlot(), null);
            player.updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (!shouldFilter(event.getPlayer()))
        {
            return;
        }

        final ItemStack item = event.getItemDrop().getItemStack();
        final Optional<String> filteredText = findFilteredItemText(item);
        if (filteredText.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        notifyAdmins(event.getPlayer(), item,
            String.format("contains prohibited text: %s", filteredText.get()));
        event.getItemDrop().remove();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
    {
        if (!shouldFilter(event.getPlayer()) || !(event.getRightClicked() instanceof Mob))
        {
            return;
        }

        final ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (item.getType() != Material.NAME_TAG)
        {
            return;
        }

        final ItemMeta meta = item.getItemMeta();
        final Optional<String> filteredText = meta == null || !meta.hasDisplayName()
            ? Optional.empty()
            : findFilteredText(meta.displayName());
        if (filteredText.isEmpty())
        {
            return;
        }

        event.setCancelled(true);
        notifyAdmins(event.getPlayer(), item, String.format("contains prohibited text: %s", filteredText.get()));
        event.getPlayer().getInventory().setItem(event.getHand(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityAddToWorld(EntityAddToWorldEvent event)
    {
        if (!(event.getEntity() instanceof Mob mob) || !filterEnabled())
        {
            return;
        }

        final Optional<String> filteredText = findFilteredText(mob.customName());
        if (filteredText.isEmpty())
        {
            return;
        }

        final Component label = Component.text("[Mob]", NamedTextColor.YELLOW)
            .hoverEvent(HoverEvent.showText(mob.customName()));
        notifyAdmins(String.format("[Mob] contains prohibited text: %s", filteredText.get()), label);
        mob.remove();
    }

    private void reloadFilters()
    {
        filters = compile(ConfigEntry.TEXT_FILTER_REGEX_FILTERS.getStringList());
        usernameFilters = compile(ConfigEntry.TEXT_FILTER_USERNAME_FILTERS.getStringList());

        FLog.info("Loaded " + filters.size() + " text filter regex pattern(s) and "
            + usernameFilters.size() + " username pattern(s).");
    }

    private static List<Pattern> compile(List<String> raw)
    {
        final List<Pattern> compiled = new ArrayList<>();

        for (String filter : raw)
        {
            if (filter == null || filter.isBlank())
            {
                continue;
            }

            try
            {
                compiled.add(Pattern.compile(filter, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            catch (PatternSyntaxException ex)
            {
                FLog.warning("Skipping invalid text filter regex: " + ex.getDescription());
            }
        }

        return List.copyOf(compiled);
    }

    private boolean shouldFilter(Player player)
    {
        return filterEnabled() && !plugin.al.isAdmin(player);
    }

    private boolean filterEnabled()
    {
        return ConfigEntry.TEXT_FILTER_ENABLED.getBoolean(true) && !filters.isEmpty();
    }

    private boolean matchesFilter(String text)
    {
        return matchesAny(filters, text);
    }

    private Optional<String> findFilteredItemText(ItemStack item)
    {
        if (item == null || !(item.getItemMeta() instanceof ItemMeta meta))
        {
            return Optional.empty();
        }

        Optional<String> filteredText = meta.hasDisplayName()
            ? findFilteredText(meta.displayName())
            : Optional.empty();
        if (filteredText.isEmpty() && meta.hasItemName())
        {
            filteredText = findFilteredText(meta.itemName());
        }
        if (filteredText.isEmpty() && meta instanceof BookMeta book)
        {
            filteredText = findFilteredText(book.getTitle())
                .or(() -> findFilteredText(book.getAuthor()))
                .or(() -> book.getPages().stream()
                    .map(this::findFilteredText)
                    .flatMap(Optional::stream)
                    .findFirst());
        }
        return filteredText;
    }

    private Optional<String> findFilteredText(String text)
    {
        return matchesFilter(text) ? Optional.of(text) : Optional.empty();
    }

    private Optional<String> findFilteredText(Component text)
    {
        return text == null ? Optional.empty() : findFilteredText(MessageUtils.toPlainText(text));
    }

    public boolean matchesUsername(String username)
    {
        return ConfigEntry.TEXT_FILTER_ENABLED.getBoolean(true) && matchesAny(usernameFilters, username);
    }

    private static boolean matchesAny(List<Pattern> patterns, String text)
    {
        if (text == null || text.isEmpty() || patterns.isEmpty())
        {
            return false;
        }

        final String folded = fold(text);
        final String deleeted = deleet(folded);

        return patterns.stream()
                       .anyMatch(filter -> filter.matcher(text).find()
                                        || filter.matcher(folded).find()
                                        || filter.matcher(deleeted).find());
    }

    private static String fold(String text)
    {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                         .replaceAll("\\p{M}+", "");
    }

    private static String deleet(String text)
    {
        final StringBuilder out = new StringBuilder(text.length());
        text.chars().forEach(c -> out.append(LEET.getOrDefault((char) c, (char) c)));

        return out.toString();
    }

    private void handleBlockedMessage(Player player, String message)
    {
        final Component feedback = MessageUtils.parse(
                "<red>[Text Filter]</red> <yellow><player></yellow>: <gray><message></gray>",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("message", message));

        player.sendMessage(MessageUtils.parse("<red>Your message was blocked by the text filter."));
        plugin.al.getOnlineAdmins().forEach(admin -> admin.sendMessage(feedback));
        FLog.warning(String.format("[TextFilter] Blocked message from %s: %s", player.getName(), message), true);
    }

    private void notifyAdmins(Player player, ItemStack item, String message)
    {
        final String itemName = itemName(item);
        final String consoleMessage = String.format("%s: [%s] %s", player.getName(), itemName, message);
        final String itemData = truncateItemData(item);
        final Component label = Component.text("[" + itemName + "]", NamedTextColor.YELLOW)
            .hoverEvent(HoverEvent.showText(Component.text("NBT: " + itemData)))
            .clickEvent(ClickEvent.copyToClipboard(itemData));
        final Component feedback = Component.text(player.getName(), NamedTextColor.GRAY)
            .append(Component.text(": "))
            .append(label)
            .append(Component.text(" " + message, NamedTextColor.GRAY));

        notifyAdmins(consoleMessage, feedback);
    }

    private static String truncateItemData(ItemStack item)
    {
        final String itemData = item == null ? "{}" : item.serialize().toString();
        if (itemData.length() <= ITEM_DATA_PREVIEW_LIMIT)
        {
            return itemData;
        }

        return itemData.substring(0, ITEM_DATA_PREVIEW_LIMIT - 3) + "...";
    }

    private static String itemName(ItemStack item)
    {
        if (item == null)
        {
            return "Item";
        }

        final String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void notifyAdmins(String message, Component feedback)
    {
        FLog.warning("[Text Filter] " + message, true);
        final Component notification = Component.text("[Text Filter] ", NamedTextColor.RED)
            .append(feedback);

        plugin.al.getOnlineAdmins().forEach(admin -> admin.sendMessage(notification));
    }
}
