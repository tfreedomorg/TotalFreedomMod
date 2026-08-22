package me.totalfreedom.totalfreedommod.rank;

import java.util.Comparator;
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
 * Ranks are defined entirely by {@code ranks.json}; nothing in code declares a tier. That inversion
 * is the point of this class. A command names only the internal permission node it needs, and the
 * tier required to reach it is derived by asking which is the least privileged rank that grants
 * that node, so re-tiering a feature is a config edit rather than a code change.
 * <p>
 * Two consequences of deriving the tier are worth knowing:
 * <ul>
 *   <li>A node granted to a low rank is reachable by everyone at or above it. Two ranks that should
 *       differ in what they may do with one feature must therefore hold <em>distinct</em> nodes; a
 *       node they share necessarily resolves to the lower of the two.</li>
 *   <li>A node no rank grants is unreachable, and its command is ungrantable rather than
 *       universally allowed. Denying is the safe direction for a typo.</li>
 * </ul>
 * The registry reads the live rank map owned by {@link RankManager} rather than copying it, so a
 * reload is visible here immediately; {@link #reindex()} rebuilds the permission index to match.
 */
public final class RankRegistry
{

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
     * The fallback used when no rank declares {@code role}.
     */
    private Optional<CustomRank> derivedRole(final RankRole role)
    {
        return switch (role)
        {
            case DEFAULT -> ordinaryRanks().min(Comparator.comparingInt(CustomRank::getLevel));
            case DEFAULT_OP -> ordinaryRanks().max(Comparator.comparingInt(CustomRank::getLevel));
            case IMPOSTOR -> ranks.values()
                                  .stream()
                                  .min(Comparator.comparingInt(CustomRank::getLevel));
            case ADMIN_DEFAULT -> adminRanks().min(Comparator.comparingInt(CustomRank::getLevel));
            case CONSOLE_FLOOR -> byRole(RankRole.ADMIN_DEFAULT);
        };
    }

    /**
     * Ranks a normal player can hold: neither staff nor the impostor marker.
     */
    private Stream<CustomRank> ordinaryRanks()
    {
        final Optional<CustomRank> impostor = ranks.values()
                                                   .stream()
                                                   .filter(rank -> rank.hasRole(RankRole.IMPOSTOR))
                                                   .findFirst();

        return ranks.values()
                    .stream()
                    .filter(rank -> !rank.isAdmin())
                    .filter(rank -> impostor.isEmpty() || !rank.equals(impostor.get()));
    }

    private Stream<CustomRank> adminRanks()
    {
        return ranks.values()
                    .stream()
                    .filter(CustomRank::isAdmin);
    }

    /**
     * The rank every unplaceable sender falls back to.
     */
    public Optional<CustomRank> floor()
    {
        return byRole(RankRole.DEFAULT);
    }

    /**
     * Whether the rank {@code rankId} sits at or above the rank filling {@code role}. An unknown
     * rank answers {@code false}, so a stale id never clears a floor it was not granted.
     */
    public boolean isAtLeast(final String rankId, final RankRole role)
    {
        final Optional<CustomRank> held = byId(rankId);
        final Optional<CustomRank> floor = byRole(role);

        return held.isPresent() && floor.isPresent()
               && held.get().getLevel() >= floor.get().getLevel();
    }

    /**
     * The least privileged rank that grants {@code permission}, which is the tier a command guarded
     * by that node actually requires. Empty when no rank grants it, meaning the node is unreachable.
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
     * The two halves answer deliberately different questions:
     * <ul>
     *   <li><b>Rank</b> is a tier comparison. The requirement resolves to the cheapest rank holding
     *       the node and any rank at or above that level passes, both sides read off the registry's
     *       own level scale.</li>
     *   <li><b>Title</b> is an exact match against that title's own grant list, with no tier
     *       involved. That asymmetry is the point: a title hands its holder one specific capability
     *       without implying anything else, so an ordinary operator can be trusted with a single
     *       permission rather than promoted past everything beneath it.</li>
     * </ul>
     */
    public boolean satisfies(final CommandSender sender, final String permission)
    {
        if (forSender(sender).map(rank -> satisfies(rank, permission)).orElse(false))
            return true;

        return plugin.tm != null && plugin.tm.grants(sender, permission);
    }

    /**
     * Whether a rank sits at or above the tier {@code permission} requires.
     */
    public boolean satisfies(final CustomRank holder, final String permission)
    {
        if (holder == null || permission == null || permission.isEmpty())
            return false;

        return requiredFor(permission).map(required -> holder.getLevel() >= required.getLevel())
                                      .orElse(false);
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
