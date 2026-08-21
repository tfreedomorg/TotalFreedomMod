package me.totalfreedom.totalfreedommod.rank;

import java.util.*;

/**
 * An index of which ranks grant which internal permission nodes.
 * <p>
 * Permission nodes are dotted paths ({@code tfm.admin.ban}) and a rank may grant them either
 * exactly or through a trailing wildcard ({@code tfm.admin.*}, or a bare {@code *} for everything).
 * Answering "which ranks grant this node" by testing every rank against every wildcard prefix is
 * what the old {@code hasCustomRankPermission} did on each check; indexing the nodes by segment
 * instead turns that into a single walk whose length is the depth of the node being asked about,
 * regardless of how many ranks or patterns exist.
 * <p>
 * The index is what lets a command declare only its permission node and have the required rank
 * <em>derived</em> from {@code ranks.json}: see {@link #lowestGranting(String, Map)}. Because it is
 * built from each rank's <em>resolved</em> permissions, inheritance is already folded in by the
 * time a node reaches the index.
 * <p>
 * Not thread-safe. Rebuild it on the main thread whenever ranks change.
 */
public final class PermissionTrie
{

    /**
     * The wildcard suffix that makes a pattern cover everything beneath its prefix.
     */
    private static final String WILDCARD = "*";

    private static final String SEPARATOR = "\\.";

    private final Node root = new Node();

    /**
     * Discards the current index and rebuilds it from {@code ranks}, reading each rank's resolved
     * permissions so that inherited grants are indexed alongside directly declared ones.
     */
    public void rebuild(final Map<String, CustomRank> ranks)
    {
        root.children.clear();
        root.exact.clear();
        root.wildcard.clear();

        ranks.values()
             .forEach(rank -> rank.getResolvedPermissions()
                                  .forEach(permission -> insert(permission, rank.getId())));
    }

    /**
     * The ids of every rank that grants {@code permission}, whether exactly or through a wildcard.
     * Returns an empty set when no rank grants it, which is the signal that a node is unreachable
     * and its command therefore ungrantable.
     */
    public Set<String> grantingRanks(final String permission)
    {
        if (permission == null || permission.isEmpty())
            return Set.of();

        final String[] segments = permission.toLowerCase().split(SEPARATOR);
        final Set<String> granting = new HashSet<>(root.wildcard);

        Node current = root;
        for (int i = 0; i < segments.length; i++)
        {
            current = current.children.get(segments[i]);
            if (current == null)
                return Collections.unmodifiableSet(granting);

            // A node's wildcard set holds the ranks granting "<path-to-here>.*", which covers
            // anything strictly below it. The terminal segment is skipped so that "a.b.c.*" is not
            // read as granting "a.b.c" itself, matching how the nodes are written in ranks.json.
            if (i < segments.length - 1)
                granting.addAll(current.wildcard);
        }

        granting.addAll(current.exact);

        return Collections.unmodifiableSet(granting);
    }

    /**
     * Whether {@code rankId} grants {@code permission}.
     */
    public boolean grants(final String rankId, final String permission)
    {
        return rankId != null && grantingRanks(permission).contains(rankId.toLowerCase());
    }

    /**
     * The cheapest rank that grants {@code permission}, or {@code null} when no rank does.
     * <p>
     * This is the inversion the command gate depends on. A handler declares only the node it needs;
     * the tier required to reach that handler is whatever the least privileged rank holding it
     * happens to be, so moving a permission between ranks in {@code ranks.json} moves the gate with
     * it and no annotation has to name a rank. Where two ranks genuinely differ in what they may do
     * with the same feature, they are expected to hold distinct nodes rather than share one, since
     * a shared node necessarily resolves to the lower of the two.
     *
     * @param ranks the registry to resolve ids against, so levels are read off one scale
     */
    public CustomRank lowestGranting(final String permission, final Map<String, CustomRank> ranks)
    {
        return grantingRanks(permission).stream()
                                        .map(ranks::get)
                                        .filter(rank -> rank != null)
                                        .min(Comparator.comparingInt(CustomRank::getLevel))
                                        .orElse(null);
    }

    /**
     * Indexes a single pattern, storing it against the node for its prefix when it ends in a
     * wildcard and against the node for the whole path otherwise.
     */
    private void insert(final String permission, final String rankId)
    {
        if (permission == null || permission.isEmpty())
            return;

        final String pattern = permission.toLowerCase();

        if (WILDCARD.equals(pattern))
        {
            root.wildcard.add(rankId);
            return;
        }

        final String[] segments = pattern.split(SEPARATOR);
        final boolean trailingWildcard = WILDCARD.equals(segments[segments.length - 1]);
        final int depth = trailingWildcard ? segments.length - 1 : segments.length;

        Node current = root;
        for (int i = 0; i < depth; i++)
        {
            current = current.children.computeIfAbsent(segments[i], ignored -> new Node());
        }

        if (trailingWildcard)
        {
            current.wildcard.add(rankId);
        }
        else
        {
            current.exact.add(rankId);
        }
    }

    /**
     * One dotted segment of the index. Mutable and deliberately package-private in behaviour: it is
     * an implementation detail of the surrounding trie and never escapes it.
     */
    private static final class Node
    {

        private final Map<String, Node> children = new HashMap<>();

        /**
         * Ranks granting the exact path ending at this node.
         */
        private final Set<String> exact = new HashSet<>();

        /**
         * Ranks granting this node's path followed by {@code .*}.
         */
        private final Set<String> wildcard = new HashSet<>();

    }

}
