package me.totalfreedom.totalfreedommod.title;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;

/**
 * A recognition a player holds alongside their rank, carrying its own display and its own narrow
 * set of capabilities.
 * <p>
 * A title is deliberately <em>not</em> a rank, and the difference is the whole point of the type.
 * Ranks form a ladder: they carry a level, they inherit from one another, and holding one implies
 * holding everything below it. That is what forced a Master Builder to be seated above Super Admin
 * merely to reach the admin world, dragging every intervening permission along with it.
 * <p>
 * A title has no level and no parent. It grants exactly the nodes listed on it and nothing else, so
 * it can hand a trusted operator one specific capability without moving them up the ladder. The
 * practical consequences:
 * <ul>
 *   <li>Title permissions are matched <em>exactly</em> (wildcards included), never by tier. Holding
 *       a title never implies holding another title's grants.</li>
 *   <li>A player may hold several titles at once; their capabilities are the union.</li>
 *   <li>{@link #getWeight()} orders titles for <em>display</em> only. A heavier title is shown in
 *       preference to a lighter one and confers nothing extra.</li>
 * </ul>
 */
public class Title implements Displayable, Comparable<Title>
{

    /**
     * Unique identifier for this title (lowercase, no spaces).
     */
    private String id;

    /**
     * Display name shown in chat and menus.
     */
    private String name;

    /**
     * Grammatical determiner (a, an, the).
     */
    private String determiner = "a";

    /**
     * Short abbreviation shown in tag brackets.
     */
    private String abbreviation;

    /**
     * Colour used for this title's display.
     */
    private NamedTextColor color = NamedTextColor.WHITE;

    /**
     * Custom prefix string for chat display, for example {@code "&8[&5Dev&8] "}.
     */
    private String prefix = null;

    /**
     * Display priority when a player holds more than one title. Higher wins. This orders
     * appearance only and grants nothing: see the class javadoc.
     */
    private int weight = 0;

    /**
     * Capabilities this title grants, as internal TFM permission strings. Matched exactly, never
     * inherited from anything, which is what keeps a title from escalating its holder.
     */
    private Set<String> permissions = new HashSet<>();

    /**
     * Whether this title may be announced in the join message in place of the player's rank.
     */
    private boolean announce = true;

    private transient Component cachedColoredTag;
    private transient Component cachedColoredName;
    private transient Component cachedColoredLoginMessage;

    public Title(String id)
    {
        this.id = normalizeId(id);
        this.name = id;
        this.abbreviation = id.length() > 3 ? id.substring(0, 3).toUpperCase() : id.toUpperCase();
        invalidateCache();
    }

    /**
     * Gson deserialises through this constructor rather than allocating the object unsafely, which
     * is what keeps the field initialisers above running. Without it an entry that omits
     * {@code announce} or {@code permissions} comes back as {@code false} and {@code null} instead
     * of carrying its documented default.
     * <p>
     * The id is deliberately left unset here: a title carries its id as the key it is filed under
     * rather than as a property of the entry, so {@link #assignId(String)} stamps it after the read.
     */
    private Title() {}

    /**
     * Stamps the id this title was filed under, normalising it exactly as the public constructor
     * does so that a title's id always matches the key it is stored against.
     */
    public void assignId(final String key)
    {
        this.id = normalizeId(key);
        invalidateCache();
    }

    /**
     * Ids reach SQL as a primary key and reach {@code players.titles} as one entry in a
     * comma-separated list, so anything that would split that list is stripped rather than stored.
     */
    public static String normalizeId(final String raw)
    {
        return raw.toLowerCase()
                  .replace(' ', '_')
                  .replaceAll("[^a-z0-9_\\-]", "");
    }

    /**
     * Whether this title grants {@code permission}.
     * <p>
     * Exact and wildcard matches only. There is deliberately no tier comparison here: a title's
     * grants are a closed list, so a node absent from it is denied no matter what else the holder
     * has.
     */
    public boolean grants(String permission)
    {
        if (permission == null || permission.isEmpty())
            return false;

        final String node = permission.toLowerCase();

        if (permissions.contains(node) || permissions.contains("*"))
            return true;

        final String[] parts = node.split("\\.");
        final StringBuilder prefixBuilder = new StringBuilder();

        for (int i = 0; i < parts.length - 1; i++)
        {
            prefixBuilder.append(parts[i]).append('.');
            if (permissions.contains(prefixBuilder + "*"))
                return true;
        }

        return false;
    }

    public void addPermission(String permission)
    {
        permissions.add(permission.toLowerCase());
    }

    public void removePermission(String permission)
    {
        permissions.remove(permission.toLowerCase());
    }

    /**
     * Invalidate cached components. Call after any property change.
     */
    public final void invalidateCache()
    {
        cachedColoredTag = null;
        cachedColoredName = null;
        cachedColoredLoginMessage = null;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String getTag()
    {
        return abbreviation == null || abbreviation.isEmpty() ? "" : "[" + abbreviation + "]";
    }

    @Override
    public NamedTextColor getColor()
    {
        return color;
    }

    @Override
    public Component getColoredName()
    {
        if (cachedColoredName == null)
        {
            cachedColoredName = AdventureUtil.format(name).colorIfAbsent(color);
        }
        return cachedColoredName;
    }

    @Override
    public Component getColoredTag()
    {
        if (cachedColoredTag == null)
        {
            if (prefix != null && !prefix.isEmpty())
            {
                cachedColoredTag = AdventureUtil.format(prefix.trim());
            }
            else if (abbreviation == null || abbreviation.isEmpty())
            {
                cachedColoredTag = Component.empty();
            }
            else
            {
                cachedColoredTag = Component.text("[")
                        .color(NamedTextColor.DARK_GRAY)
                        .append(AdventureUtil.format(abbreviation).colorIfAbsent(color))
                        .append(Component.text("]").color(NamedTextColor.DARK_GRAY));
            }
        }
        return cachedColoredTag;
    }

    @Override
    public Component getColoredLoginMessage()
    {
        if (cachedColoredLoginMessage == null)
        {
            cachedColoredLoginMessage = Component.text(determiner + " ")
                    .append(AdventureUtil.format(name)
                            .colorIfAbsent(color)
                            .decorate(TextDecoration.ITALIC));
        }
        return cachedColoredLoginMessage;
    }

    /**
     * Orders by display weight, heaviest first, so the natural order is the order titles should be
     * preferred in when a player holds several.
     */
    @Override
    public int compareTo(Title other)
    {
        final int byWeight = Integer.compare(other.weight, this.weight);

        // Tie-break on id so that ordering only calls two titles equal when equals() does. A sorted
        // set would otherwise treat every same-weight title as a duplicate and keep just one.
        return byWeight != 0 ? byWeight : id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (!(obj instanceof Title other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode()
    {
        return id.hashCode();
    }

    @Override
    public String toString()
    {
        return String.format("Title{id=%s, name=%s, weight=%d}", id, name, weight);
    }
    
    public String getId()
    {
        return id;
    }

    public void setName(String name)
    {
        this.name = name;
        invalidateCache();
    }

    public String getDeterminer()
    {
        return determiner;
    }

    public void setDeterminer(String determiner)
    {
        this.determiner = determiner;
        invalidateCache();
    }

    public String getAbbreviation()
    {
        return abbreviation;
    }

    public void setAbbreviation(String abbreviation)
    {
        this.abbreviation = abbreviation;
        invalidateCache();
    }

    public void setColor(NamedTextColor color)
    {
        this.color = color;
        invalidateCache();
    }

    public String getPrefix()
    {
        return prefix;
    }

    public void setPrefix(String prefix)
    {
        this.prefix = prefix;
        invalidateCache();
    }

    public int getWeight()
    {
        return weight;
    }

    public void setWeight(int weight)
    {
        this.weight = weight;
    }

    public Set<String> getPermissions()
    {
        return Collections.unmodifiableSet(permissions);
    }

    /**
     * Replaces the grant list, lower-casing as {@link #addPermission(String)} does so that a set
     * installed through here still matches in {@link #grants(String)}.
     */
    public void setPermissions(Set<String> permissions)
    {
        this.permissions = permissions == null
                ? new HashSet<>()
                : permissions.stream()
                             .map(String::toLowerCase)
                             .collect(Collectors.toCollection(HashSet::new));
    }

    public boolean isAnnounce()
    {
        return announce;
    }

    public void setAnnounce(boolean announce)
    {
        this.announce = announce;
    }

}
