package me.totalfreedom.totalfreedommod.rank;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;

/**
 * The single place that answers "what rank is this?" and "what rank does this permission need?".
 * <p>
 * Ranks are defined entirely by {@code ranks.json}; nothing in code declares a tier. A user holds a
 * rank, a rank holds permission nodes, and a check asks whether the rank of the sender holds the
 * node. Rank levels never take part in that decision, so a rank is neither implicitly granted what
 * sits below it nor implicitly denied what it inherits; inheritance is folded into each rank's
 * resolved nodes before they are indexed.
 * <p>
 * Two kinds of node are checked this way:
 * <ul>
 *   <li>A <b>permission</b> such as {@code tfm.admin.ban} gates a capability.</li>
 *   <li>A <b>marker</b>, always {@code tfm.marker.<tag>}, labels a rank with a piece of metadata
 *       (for example {@link #ADMIN_MARKER}) instead of gating anything.</li>
 * </ul>
 * Every node is internal to TFM and is never registered with Bukkit. All players on the server are
 * opped, so a node Bukkit could see would be held by everyone.
 * <p>
 * A node no rank grants is unreachable, and its command is ungrantable rather than universally
 * allowed. Denying is the safe direction for a typo.
 * <p>
 * The registry reads the live rank map owned by {@link RankManager} rather than copying it, so a
 * reload is visible here immediately; {@link #reindex()} rebuilds the permission index to match.
 */
public final class RankRegistry
{

    /**
     * The prefix every marker node carries, so a marker is never mistaken for a capability.
     */
    public static final String MARKER_PREFIX = "tfm.marker.";

    /**
     * The tag of the marker that labels a rank as staff.
     */
    public static final String ADMIN_TAG = "admin";

    /**
     * The full node of the staff marker, as written in {@code ranks.json}.
     */
    public static final String ADMIN_MARKER = MARKER_PREFIX + ADMIN_TAG;

    /**
     * The tag of the marker that labels the display-only rank shown for a player flagged as
     * impersonating an admin.
     */
    public static final String IMPOSTOR_TAG = "impostor";

    /**
     * The nodes {@code PermissionGate} demands of an identified remote session, one per channel.
     * A rank holding all of them is trusted with the remote channels.
     */
    private static final List<String> CHANNEL_NODES = List.of("tfm.manage.ssh", "tfm.manage.discord", "tfm.manage.telnet");

    private final TotalFreedomMod plugin;

    /**
     * The live rank map owned by {@link RankManager}, read but never mutated here.
     */
    private final Map<String, CustomRank> ranks;

    private final PermissionTrie trie = new PermissionTrie();

    /**
     * Resolves any console-class sender: an identified SSH or Discord session becomes the admin
     * behind it, and anything else falls back to its {@code host_senders:} binding.
     */
    private final RankResolver<CommandSender> consoleResolver;

    private final RankResolver<Player> playerResolver;

    public RankRegistry(final TotalFreedomMod plugin, final Map<String, CustomRank> ranks)
    {
        this.plugin = plugin;
        this.ranks = ranks;
        this.playerResolver = this::resolvePlayer;
        this.consoleResolver = ((RankResolver<CommandSender>) this::resolveDispatch)
                .orElse(this::resolveAdminByName)
                .orElse(this::resolveBoundSender);
    }

    /**
     * Rebuilds the permission index from the current rank map. Call after ranks are loaded or after
     * inheritance is re-resolved, on the main thread.
     */
    public void reindex()
    {
        trie.rebuild(ranks);
    }

    public PermissionTrie getTrie()
    {
        return trie;
    }

    /**
     * The rank registered under {@code id}, if any.
     */
    public Optional<CustomRank> byId(final String id)
    {
        return id == null ? Optional.empty() : Optional.ofNullable(ranks.get(id.toLowerCase()));
    }

    /**
     * The rank filling {@code role}, preferring one that declares it in {@code ranks.json} and
     * otherwise deriving it from the shape of the registry.
     * <p>
     * Nothing in code names a rank, so a designated staff member may rename, re-tier or delete any of them and
     * the roles follow. When two ranks declare the same role the lower-level one wins, which keeps
     * the answer stable rather than depending on map order.
     */
    public Optional<CustomRank> byRole(final RankRole role)
    {
        final Optional<CustomRank> declared = ranks.values()
                                                   .stream()
                                                   .filter(rank -> rank.hasRole(role))
                                                   .min(Comparator.comparingInt(CustomRank::getLevel));

        return declared.isPresent() ? declared : derivedRole(role);
    }

    /**
     * The fallback used when no rank declares {@code role}. Each one is read off the permissions and
     * markers the ranks hold, never off a rank's name or a guess at its level, and the privileged
     * ones lean toward granting less: a role no rank can be derived for stays empty, which denies.
     */
    private Optional<CustomRank> derivedRole(final RankRole role)
    {
        return switch (role)
        {
            case DEFAULT -> ordinaryRanks().min(Comparator.comparingInt(CustomRank::getLevel));
            case DEFAULT_OP -> ordinaryRanks().max(Comparator.comparingInt(CustomRank::getLevel));
            case IMPOSTOR -> ranks.values()
                                  .stream()
                                  .filter(rank -> hasMarker(rank, IMPOSTOR_TAG))
                                  .min(Comparator.comparingInt(CustomRank::getLevel));
            case ADMIN_DEFAULT -> leastPrivileged(adminRanks());
            case CONSOLE_FLOOR -> leastPrivileged(adminRanks().filter(this::holdsChannelNodes));
        };
    }

    /**
     * Ranks a normal player can hold: neither staff nor the impostor marker.
     */
    private Stream<CustomRank> ordinaryRanks()
    {
        return ranks.values()
                    .stream()
                    .filter(rank -> !hasMarker(rank, ADMIN_TAG))
                    .filter(rank -> !hasMarker(rank, IMPOSTOR_TAG))
                    .filter(rank -> !rank.hasRole(RankRole.IMPOSTOR));
    }

    private Stream<CustomRank> adminRanks()
    {
        return ranks.values()
                    .stream()
                    .filter(rank -> hasMarker(rank, ADMIN_TAG));
    }

    /**
     * The candidate that grants the least, judged by what each grants rather than by node count or
     * level: a rank is above another when it grants everything the other does and more, so a
     * wildcard rank outranks one that lists its nodes out. Candidates that grant incomparable sets
     * are told apart by level, then id, so the answer is stable.
     */
    private Optional<CustomRank> leastPrivileged(final Stream<CustomRank> candidates)
    {
        final List<CustomRank> pool = candidates.toList();

        return pool.stream()
                   .filter(rank -> pool.stream().noneMatch(other -> isStrictlyAbove(rank, other)))
                   .min(Comparator.comparingInt(CustomRank::getLevel).thenComparing(CustomRank::getId));
    }

    private boolean isStrictlyAbove(final CustomRank rank, final CustomRank other)
    {
        return covers(rank, other) && !covers(other, rank);
    }

    private boolean holdsChannelNodes(final CustomRank rank)
    {
        return CHANNEL_NODES.stream()
                            .allMatch(node -> satisfies(rank, node));
    }

    /**
     * The rank every unplaceable sender falls back to.
     */
    public Optional<CustomRank> floor()
    {
        return byRole(RankRole.DEFAULT);
    }

    /**
     * Whether {@code holder} grants every node {@code other} grants, which is what it means for one
     * rank to be able to do everything another can. Compared node by node through the index, so a
     * wildcard on {@code holder} covers the nodes it spans.
     */
    public boolean covers(final CustomRank holder, final CustomRank other)
    {
        return other.getResolvedPermissions()
                    .stream()
                    .allMatch(node -> trie.grants(holder.getId(), node));
    }

    /**
     * The least privileged rank that grants {@code permission}. Empty when no rank grants it,
     * meaning the node is unreachable.
     * <p>
     * This picks a rank to <em>assign</em> or to order a listing by; it is not an access check, and
     * holding a higher level than the result grants nothing. Use {@link #satisfies} to gate.
     */
    public Optional<CustomRank> requiredFor(final String permission)
    {
        return Optional.ofNullable(trie.lowestGranting(permission, ranks));
    }

    /**
     * The rank {@code sender} acts at, falling back to {@link #floor()} when it cannot be placed.
     * <p>
     * Command blocks and command minecarts are deliberately pinned to the floor: they are world
     * state that any player can create, so treating them as an identity would let anyone mint one.
     */
    public Optional<CustomRank> forSender(final CommandSender sender)
    {
        if (sender == null)
            return Optional.empty();

        if (sender instanceof Player player)
            return playerResolver.resolve(player).or(this::floor);

        if (sender instanceof BlockCommandSender || sender instanceof CommandMinecart)
            return floor();

        return consoleResolver.resolve(sender).or(this::floor);
    }

    /**
     * Whether {@code sender} may exercise {@code permission}, by rank or by title.
     * <p>
     * <b>Rank</b> is a membership test: the sender's rank either grants the node, directly, by
     * wildcard, or through inheritance, or it does not. <b>Title</b> is an exact match against that
     * title's own grant list. Either way a holder gets precisely what was granted and nothing
     * implied, so an ordinary operator can be trusted with a single permission without being
     * promoted past everything beneath it.
     */
    public boolean satisfies(final CommandSender sender, final String permission)
    {
        if (forSender(sender).map(rank -> satisfies(rank, permission)).orElse(false))
            return true;

        return plugin.tm != null && plugin.tm.grants(sender, permission);
    }

    /**
     * Whether {@code holder} grants {@code permission}.
     */
    public boolean satisfies(final CustomRank holder, final String permission)
    {
        if (holder == null || permission == null || permission.isEmpty())
            return false;

        return trie.grants(holder.getId(), permission);
    }

    /**
     * Whether the rank {@code sender} acts at carries the marker {@code tag}, that is, holds
     * {@code tfm.marker.<tag>}. Markers describe a rank; a title never confers one.
     */
    public boolean hasMarker(final CommandSender sender, final String tag)
    {
        return forSender(sender).map(rank -> hasMarker(rank, tag)).orElse(false);
    }

    /**
     * Whether {@code rank} carries the marker {@code tag}.
     */
    public boolean hasMarker(final CustomRank rank, final String tag)
    {
        return satisfies(rank, MARKER_PREFIX + tag);
    }

    /**
     * The admin behind an identified dispatch session.
     * <p>
     * SSH and Discord prove a real identity, so a session that carries one resolves to that admin's
     * own rank rather than to the flat tier the channel is bound to. A password-only SSH session
     * proves nothing and declines here, as does any session when {@code ssh.inherit_rank} is off.
     */
    private Optional<CustomRank> resolveDispatch(final CommandSender sender)
    {
        final RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();
        if (dispatch == null)
            return Optional.empty();

        if (dispatch.isIdentified()
            && !(dispatch.getChannel() == RemoteDispatchSession.Channel.SSH
                 && !ConfigEntry.SSH_INHERIT_RANK.getBoolean()))
        {
            final Optional<CustomRank> identity = adminRank(plugin.al.getEntryByName(dispatch.getUsername()));
            if (identity.isPresent())
                return identity;
        }

        final String channel = dispatch.getChannel() == RemoteDispatchSession.Channel.DISCORD
                ? "discord"
                : "ssh";

        return boundRank(channel);
    }

    private Optional<CustomRank> resolveAdminByName(final CommandSender sender)
    {
        return adminRank(plugin.al.getEntryByName(sender.getName()));
    }

    private Optional<CustomRank> resolveBoundSender(final CommandSender sender)
    {
        return boundRank(sender.getName());
    }

    private Optional<CustomRank> resolvePlayer(final Player player)
    {
        if (plugin.al.isAdminImpostor(player))
            return byRole(RankRole.IMPOSTOR);

        final Optional<CustomRank> assigned = adminRank(plugin.al.getAdmin(player));
        if (assigned.isPresent())
            return assigned;

        return byRole(player.isOp() ? RankRole.DEFAULT_OP : RankRole.DEFAULT);
    }

    /**
     * An active admin's rank, or empty when the profile is missing or deactivated.
     */
    private Optional<CustomRank> adminRank(final Admin admin)
    {
        if (admin == null || !admin.isActive())
            return Optional.empty();

        final Optional<CustomRank> held = byId(admin.getRankId());

        return held.isPresent() ? held : byRole(RankRole.ADMIN_DEFAULT);
    }

    /**
     * The rank bound to a no-identity sender by {@code host_senders:}.
     */
    private Optional<CustomRank> boundRank(final String senderName)
    {
        return plugin.csr == null
                ? Optional.empty()
                : byId(plugin.csr.getRankIdForSender(senderName));
    }

}
