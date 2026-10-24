package me.totalfreedom.totalfreedommod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.*;

public class BookSpy extends FreedomService
{
	private static final Duration VIEW_LIFETIME = Duration.ofMinutes(10);
	private static final int SUMMARY_CHARS = 60;
	private static final int MAX_CHANGE_CHARS = 30;
	private static final int MIN_CHANGE_CHARS = 8;
	private static final int MAX_LISTED_PAGES = 5;
	private static final int MAX_BOOK_PAGES = 100;
	private static final Component UNTITLED = Component.text("Untitled");
	private static final Component UNSAFE_PAGE = Component.text("[unsafe page withheld]");

	private record BookSnapshot(Component title, String author, List<Component> newPages, int firstChangedPage)
	{
	}

	private record PageChange(int page, boolean added, String text)
	{
	}

	public BookSpy(TotalFreedomMod plugin)
	{
		super(plugin);
	}

	@Override
	protected void onStart()
	{
	}

	@Override
	protected void onStop()
	{
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerEditBook(PlayerEditBookEvent event)
	{
		final Player editor = event.getPlayer();
		final BookMeta oldMeta = event.getPreviousBookMeta();
		final BookMeta newMeta = event.getNewBookMeta();
		final List<Component> oldPages = sanitize(oldMeta.pages());
		final List<Component> newPages = sanitize(newMeta.pages());

		if (!event.isSigning() && oldPages.equals(newPages))
		{
			return;
		}

		if (!event.isSigning() && isBlank(newPages))
		{
			return;
		}

		final boolean editorIsAdmin = plugin.al.isAdmin(editor);
		final Component rawTitle = newMeta.hasTitle() ? newMeta.title() : UNTITLED;
		final Component title = isCursed(rawTitle) ? UNTITLED : rawTitle;
		final List<PageChange> changes = diff(oldPages, newPages);
		final BookSnapshot snapshot = new BookSnapshot(title, editor.getName(), newPages,
				changes.isEmpty() ? 0 : changes.get(0).page());

		Component message = Component.empty();
		if (editorIsAdmin)
		{
			final Displayable display = plugin.rm.getDisplay(editor);
			String prefix = AdventureUtil.componentToPlainText(display.getColoredTag()).trim();
			if (prefix.isEmpty())
			{
				// A rank is free to report no tag at all, so fall back to the empty string
				// rather than letting a null reach the isEmpty() check below
				final String tag = display.getTag();
				prefix = tag != null ? tag : "";
			}
			if (!prefix.isEmpty())
			{
				message = Component.text(String.format("%s ", prefix), display.getColor());
			}
		}

		final StringBuilder action = new StringBuilder(editor.getName());
		if (event.isSigning())
		{
			action.append(String.format(" signed book '%s'", AdventureUtil.componentToPlainText(title).trim()));
		}
		else
		{
			action.append(" edited book");
		}
		if (!changes.isEmpty())
		{
			action.append(String.format(" (%s)", pageLabel(changes)));
		}
		message = message.append(Component.text(action.toString(), NamedTextColor.GRAY));

		int budget = SUMMARY_CHARS;
		int shown = 0;
		for (final PageChange change : changes)
		{
			if (shown > 0 && budget < MIN_CHANGE_CHARS)
			{
				break;
			}
			final int room = Math.min(budget, MAX_CHANGE_CHARS);
			final String text = change.text().length() > room
					? String.format("%s[...]", change.text().substring(0, room)) : change.text();
			message = message.append(Component.text(shown == 0 ? " " : ", ", NamedTextColor.GRAY))
					.append(Component.text(change.added() ? "+" : "-",
							change.added() ? NamedTextColor.GREEN : NamedTextColor.RED))
					.append(Component.text(String.format("'%s'", text), NamedTextColor.GRAY));
			budget -= text.length();
			shown++;
		}

		if (shown < changes.size())
		{
			message = message.append(Component.text(String.format(" (and %d more)", changes.size() - shown),
					NamedTextColor.GRAY));
		}

		message = message.append(Component.text(" [", NamedTextColor.GRAY));
		if (!isBlank(oldPages))
		{
			message = message.append(viewButton("See Edit", "Click to read the first edited page", snapshot, false))
					.append(Component.text(" | ", NamedTextColor.GRAY));
		}
		message = message.append(viewButton("Read Book", "Click to read the whole book from page 1", snapshot, true))
				.append(Component.text("]", NamedTextColor.GRAY));

		for (final Player admin : plugin.al.getOnlineAdmins())
		{
			if (admin.equals(editor))
			{
				continue;
			}
			final PlayerData data = plugin.pl.getData(admin);
			if (data == null || !data.getBookSpyMode().shows(editorIsAdmin))
			{
				continue;
			}
			FUtil.playerMsg(admin, message);
		}
	}

	/**
	 * Drop the component graph of any page that fails inspection, and cap the page count.
	 * A spy reads this content back through the view buttons, so a book crafted to carry a
	 * malicious graph must not be relayed to their client verbatim.
	 */
	private static List<Component> sanitize(final List<Component> pages)
	{
		return pages.stream()
					.limit(MAX_BOOK_PAGES)
					.map(page -> isCursed(page) ? UNSAFE_PAGE : page)
					.toList();
	}

	private static boolean isCursed(final Component component)
	{
		return ComponentScanner.isCursed(component, ConfigEntry.maxComponentNodes());
	}

	private static boolean isBlank(final List<Component> pages)
	{
		return pages.stream()
					.map(AdventureUtil::componentToPlainText)
					.map(String::trim)
					.allMatch(String::isEmpty);
	}

	private static List<PageChange> diff(final List<Component> oldPages, final List<Component> newPages)
	{
		final int pageCount = Math.max(oldPages.size(), newPages.size());
		final List<PageChange> changes = new ArrayList<>(pageCount);
		for (int i = 0; i < pageCount; i++)
		{
			final List<String> before = lines(oldPages, i);
			final List<String> now = lines(newPages, i);
			if (before.equals(now))
			{
				continue;
			}
			for (final String line : now)
			{
				if (!before.contains(line))
				{
					changes.add(new PageChange(i, true, line));
				}
			}
			for (final String line : before)
			{
				if (!now.contains(line))
				{
					changes.add(new PageChange(i, false, line));
				}
			}
		}
		return changes;
	}

	private static List<String> lines(final List<Component> pages, final int index)
	{
		if (index >= pages.size())
		{
			return List.of();
		}
		return AdventureUtil.componentToPlainText(pages.get(index))
							.lines()
							.map(String::trim)
							.filter(line -> !line.isEmpty())
							.toList();
	}

	private static String pageLabel(final List<PageChange> changes)
	{
		final List<Integer> pages = changes.stream()
										   .map(PageChange::page)
										   .distinct()
										   .toList();

		final StringBuilder label = new StringBuilder(pages.size() == 1 ? "page " : "pages ");
		label.append(String.join(", ", pages.stream()
											.limit(MAX_LISTED_PAGES)
											.map(page -> String.valueOf(page + 1))
											.toList()));
		if (pages.size() > MAX_LISTED_PAGES)
		{
			label.append(String.format(" and %d more", pages.size() - MAX_LISTED_PAGES));
		}
		return label.toString();
	}

	private Component viewButton(final String label, final String hover, final BookSnapshot snapshot,
								 final boolean wholeBook)
	{
		return Component.text(label, NamedTextColor.YELLOW)
				.clickEvent(ClickEvent.callback(
						audience ->
						{
							if (audience instanceof Player viewer)
							{
								Bukkit.getScheduler().runTask(plugin, FTask.guard("BookSpy/openBook",
										() -> openBook(viewer, snapshot, wholeBook)));
							}
						},
						ClickCallback.Options.builder()
								.uses(ClickCallback.UNLIMITED_USES)
								.lifetime(VIEW_LIFETIME)
								.build()))
				.hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
	}

	private void openBook(final Player viewer, final BookSnapshot snapshot, final boolean wholeBook)
	{
		if (!viewer.isOnline()) return;

		final List<Component> source = snapshot.newPages();
		// openBook cannot be told which page to open at, so the copy handed to the viewer
		// starts at the edited page instead.
		final int start = wholeBook ? 0 : Math.min(snapshot.firstChangedPage(), Math.max(source.size() - 1, 0));
		final List<Component> pages = source.isEmpty()
				? List.of(Component.empty()) : List.copyOf(source.subList(start, source.size()));

		final ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
		book.editMeta(BookMeta.class, meta ->
		{
			meta.title(snapshot.title());
			meta.author(Component.text(snapshot.author()));
			meta.pages(pages);
		});

		viewer.closeInventory();
		viewer.openBook(book);
	}
}