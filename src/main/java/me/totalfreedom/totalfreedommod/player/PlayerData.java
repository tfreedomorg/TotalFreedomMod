package me.totalfreedom.totalfreedommod.player;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.player.IPlayerData;
import me.totalfreedom.api.PluginProvider;
import me.totalfreedom.totalfreedommod.caging.CageData;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.freeze.FreezeData;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.Sets;

/**
 * Everything the plugin knows about one player: what gets saved to storage, plus the state that
 * only lives for the current session.
 * <p>
 * Records are keyed by {@link #getUuid()}. A record created from a name alone (an import, or a file
 * written before records carried a UUID) starts under {@link #offlineUuid(String)}. That is already
 * the right key on an offline-mode server; on an online-mode one, {@link PlayerList} moves the record
 * to the player's real UUID the first time they join. A staff record never moves by name alone,
 * since the name may have changed hands: console moves it with {@code /saconfig rekey}.
 * <p>
 * Session state is {@code transient}, so Gson never writes it and every session starts from the
 * field defaults. Instances come out of storage unbound; {@link PlayerList} binds them to the
 * plugin before handing them out. Until then the setters only change fields, which keeps a
 * repository load from scheduling tasks or saving back what it just read.
 */
public class PlayerData implements IPlayerData
{
    private static final long COUNTER_WINDOW_MS = 1000L;
    private static final String DEFAULT_ALERT_SOUND = "entity.ender_dragon.growl";
    private static volatile long MESSAGE_COUNTER_WINDOW_MS = 1000L;

    private static final String DISCORD_IDENTIFIER_ALPHABET = "0123456789ABCDEF";
    private static final int DISCORD_IDENTIFIER_LENGTH = 4;
    private static final SecureRandom DISCORD_IDENTIFIER_RANDOM = new SecureRandom();

    private static final int MAX_TRUSTED_IPS = 5;

    public static final int MAX_STRIKES = 3;
    public static final long AUTO_PURGE_TICKS = 5L * 60L * 20L;

    private UUID uuid;
    private String username;
    private long firstJoinUnix;
    private long lastJoinUnix;
    private SpyMode potionSpyMode = SpyMode.OFF;
    private SpyMode signSpyMode = SpyMode.OFF;
    private SpyMode bookSpyMode = SpyMode.OFF;
    private SpyMode commandSpyMode = SpyMode.OFF;
    private String commandSpyAlertSound = DEFAULT_ALERT_SOUND;
    private String commandSpyAlertRegex;
    private boolean muted;
    private boolean frozen;
    private boolean commandsBlocked;
    private boolean joinLeaveMessagesEnabled = true;
    private String savedTag;
    private Component nickname;
    private int strikes;

    /**
     * The address this player was last seen on. For staff it only moves once a session has passed
     * the identity check, see {@link PlayerList}.
     */
    private String ip;

    /**
     * The addresses a staff member's sessions are recognised from, least recently used first.
     * Capped at {@link #MAX_TRUSTED_IPS} so a home connection, a phone hotspot and a rotating IPv6
     * address can all pass without the list growing forever; the oldest drops off when a new one
     * comes in.
     */
    private final List<String> trustedIps = new ArrayList<>();

    /**
     * The staff rank id from {@code ranks.json}, or {@code null} for a regular player.
     */
    private String rankId;

    /**
     * Whether the rank applies. Inactive staff keep their rank on record but hold none of it, which
     * is how the inactivity cleanup retires an admin without losing who they were.
     */
    private boolean adminActive = true;
    private String loginMessage;
    private String discordId;

    /**
     * A short code such as {@code #1D8C}, unique among linked accounts, that the Discord side uses
     * to find this record without going through the UUID.
     */
    private String discordIdentifier;

    /**
     * Ids of the titles this player holds.
     * <p>
     * A player may hold several at once; their capabilities are the union of what those titles
     * grant, and no title implies any other. Stored as ids rather than as resolved titles so that
     * deleting a title simply stops it applying instead of stranding every holder.
     */
    private final Set<String> titles = Sets.newLinkedHashSet();

    private transient FreedomAPI plugin;
    private transient Player player;
    private transient Pattern commandSpyAlertPattern;
    private transient BukkitTask unmuteTask;
    private final transient FreezeData freezeData = new FreezeData(this);
    private final transient CageData cageData = new CageData(this);
    private final transient ChatSpamData chatSpamData = new ChatSpamData();
    private transient double fuckoffRadius = 0;
    private transient int messageCount = 0;
    private transient long messageCountWindowStart = 0L;
    private transient int dropCount = 0;
    private transient long dropCountWindowStart = 0L;
    private transient int dropItemCount = 0;
    private transient long dropItemCountWindowStart = 0L;
    private transient int totalBlockDestroy = 0;
    private transient long totalBlockDestroyWindowStart = 0L;
    private transient int totalBlockPlace = 0;
    private transient long totalBlockPlaceWindowStart = 0L;
    private transient int freecamDestroyCount = 0;
    private transient int freecamPlaceCount = 0;
    private transient boolean orbiting = false;
    private transient double orbitStrength = 10.0;
    private transient BukkitTask mp44ScheduleTask = null;
    private transient boolean mp44Armed = false;
    private transient boolean mp44Firing = false;
    private transient BukkitTask lockupScheduleTask = null;
    private transient boolean inAdminChat = false;
    private transient Component tag = null;
    private transient String tagInternal = null;
    private transient int warningCount = 0;

    public PlayerData(Player player)
    {
        this.uuid = player.getUniqueId();
        this.username = player.getName();
    }

    /**
     * A record known only by name. Keyed by {@link #offlineUuid(String)} until a join moves it.
     */
    public PlayerData(String username)
    {
        this.uuid = offlineUuid(username);
        this.username = username;
    }

    /**
     * For Gson. Having a no-argument constructor makes Gson run the field initialisers, so the
     * session state above exists and fields missing from older files keep their defaults.
     */
    private PlayerData() {}

    /**
     * The UUID an offline-mode server gives {@code username}, computed the same way vanilla does.
     * Case-sensitive, like vanilla.
     */
    public static UUID offlineUuid(String username)
    {
        return UUID.nameUUIDFromBytes(String.format("OfflinePlayer:%s", username).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A fresh {@code #XXXX} Discord identifier that {@code taken} doesn't already claim.
     */
    public static String newDiscordIdentifier(Predicate<String> taken)
    {
        String identifier;
        do
            identifier = randomDiscordIdentifier();
        while (taken.test(identifier));

        return identifier;
    }

    public static void refreshConfig()
    {
        Optional.ofNullable(ConfigEntry.ANTISPAM_TIME_WINDOW.getInteger())
                .filter(window -> window > 0)
                .ifPresent(window -> MESSAGE_COUNTER_WINDOW_MS = window);
    }

    public FreedomAPI getPlugin()
    {
        return plugin;
    }

    @Override
    public UUID getUuid()
    {
        return uuid;
    }

    public void setUuid(UUID uuid)
    {
        this.uuid = uuid;
    }

    @Override
    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    /**
     * The online player this data belongs to, or {@code null} when they are offline.
     */
    @Override
    public Player getPlayer()
    {
        if (player != null && !player.isOnline())
            player = null;

        if (player == null)
            player = Bukkit.getPlayerExact(username);

        return player;
    }

    /**
     * The address of the current session when online, otherwise the most recently stored one.
     */
    @Override
    public String getIp()
    {
        return Optional.ofNullable(getPlayer())
                       .map(Player::getAddress)
                       .map(address -> address.getAddress().getHostAddress())
                       .orElse(ip);
    }

    @Override
    public long getFirstJoinUnix()
    {
        return firstJoinUnix;
    }

    public void setFirstJoinUnix(long firstJoinUnix)
    {
        this.firstJoinUnix = firstJoinUnix;
    }

    @Override
    public long getLastJoinUnix()
    {
        return lastJoinUnix;
    }

    public void setLastJoinUnix(long lastJoinUnix)
    {
        this.lastJoinUnix = lastJoinUnix;
    }

    public boolean isPotionSpy()
    {
        return getPotionSpyMode() != SpyMode.OFF;
    }

    public SpyMode getPotionSpyMode()
    {
        return potionSpyMode == null ? SpyMode.OFF : potionSpyMode;
    }

    public void setPotionSpyMode(SpyMode potionSpyMode)
    {
        this.potionSpyMode = potionSpyMode == null ? SpyMode.OFF : potionSpyMode;
    }

    @Override
    public boolean isMuted()
    {
        return muted;
    }

    /**
     * Once bound, muting an online player also schedules the automatic unmute, and any change is
     * saved straight away.
     */
    public void setMuted(boolean muted)
    {
        FUtil.cancel(unmuteTask);
        unmuteTask = null;

        final boolean changed = this.muted != muted;
        this.muted = muted;

        if (plugin == null)
            return;

        if (muted && getPlayer() != null)
        {
            unmuteTask = plugin.getServer().getScheduler().runTaskLater(plugin,
                    FTask.guard("PlayerData/autoUnmute", () ->
                    {
                        FUtil.adminAction("TotalFreedom", String.format("Unmuting %s", username), false);
                        setMuted(false);
                    }), AUTO_PURGE_TICKS);
        }

        if (changed)
            plugin.players().saveData(this);
    }

    @Override
    public boolean isFrozen()
    {
        return frozen;
    }

    public void setFrozen(boolean frozen)
    {
        this.frozen = frozen;
    }

    @Override
    public boolean isCommandsBlocked()
    {
        return commandsBlocked;
    }

    /**
     * Once bound, a change is saved straight away.
     */
    public void setCommandsBlocked(boolean commandsBlocked)
    {
        final boolean changed = this.commandsBlocked != commandsBlocked;
        this.commandsBlocked = commandsBlocked;

        if (changed && plugin != null)
            plugin.players().saveData(this);
    }

    public String getSavedTag()
    {
        return savedTag;
    }

    public void setSavedTag(String savedTag)
    {
        this.savedTag = savedTag;
    }

    public Component getNickname()
    {
        return nickname;
    }

    @Override
    public int getStrikes()
    {
        return strikes;
    }

    @Override
    public void loadFrom(ConfigurationSection cs)
    {
        this.username = cs.getString("username", username);
        final List<String> legacyIps = cs.getStringList("ips");
        this.ip = legacyIps.isEmpty() ? null : legacyIps.getLast();
        this.firstJoinUnix = cs.getLong("first_join", 0);
        this.lastJoinUnix = cs.getLong("last_join", 0);
        this.potionSpyMode = SpyMode.fromStorage(cs.getString("potion_spy_mode", cs.getBoolean("potion_spy", false) ? "all" : "off"));
        this.signSpyMode = SpyMode.fromStorage(cs.getString("sign_spy_mode", cs.getBoolean("sign_spy", false) ? "all" : "off"));
        this.bookSpyMode = SpyMode.fromStorage(cs.getString("book_spy_mode", "off"));
        final boolean legacyCommandSpy = cs.getBoolean("command_spy", false);
        this.commandSpyMode = SpyMode.fromStorage(cs.getString("command_spy_mode", legacyCommandSpy ? "ops" : "off"));
        this.commandSpyAlertSound = cs.getString("command_spy_alert_sound", DEFAULT_ALERT_SOUND);
        this.commandSpyAlertRegex = cs.getString("command_spy_alert_regex");
        compileCommandSpyAlertRegex();
        this.muted = cs.getBoolean("muted", false);
        this.frozen = cs.getBoolean("frozen", false);
        this.commandsBlocked = cs.getBoolean("commands_blocked", false);
        this.joinLeaveMessagesEnabled = cs.getBoolean("join_leave_messages", true);
        this.strikes = cs.getInt("strikes", 0);
        setTitles(cs.getStringList("titles"));
        this.savedTag = cs.getString("saved_tag");
        if (this.savedTag != null && this.savedTag.isEmpty())
        {
            this.savedTag = null;
        }
        final String rawNickname = cs.getString("nickname", null);
        this.nickname = rawNickname != null && !rawNickname.isEmpty() ? AdventureUtil.legacyToComponent(rawNickname) : null;
        if (this.nickname != null && !hasCustomNickname())
        {
            this.nickname = null;
        }
    }

    public boolean isCommandSpy()
    {
        return getCommandSpyMode() != SpyMode.OFF;
    }

    public void setCommandSpy(boolean commandSpy)
    {
        this.commandSpyMode = commandSpy ? SpyMode.ALL : SpyMode.OFF;
    }

    public SpyMode getCommandSpyMode()
    {
        return commandSpyMode == null ? SpyMode.OFF : commandSpyMode;
    }

    public void setCommandSpyMode(SpyMode commandSpyMode)
    {
        this.commandSpyMode = commandSpyMode == null ? SpyMode.OFF : commandSpyMode;
    }

    public Sound getCommandSpyAlertSound()
    {
        if (commandSpyAlertSound == null || commandSpyAlertSound.isBlank())
        {
            return Sound.ENTITY_ENDER_DRAGON_GROWL;
        }

        final NamespacedKey key = NamespacedKey.fromString(commandSpyAlertSound);
        if (key == null)
        {
            return Sound.ENTITY_ENDER_DRAGON_GROWL;
        }

        final Sound sound = Registry.SOUNDS.get(key);
        return sound == null ? Sound.ENTITY_ENDER_DRAGON_GROWL : sound;
    }

    public void setCommandSpyAlertSound(Sound commandSpyAlertSound)
    {
        this.commandSpyAlertSound = commandSpyAlertSound == null
            ? DEFAULT_ALERT_SOUND
                : Registry.SOUNDS.getKeyOrThrow(commandSpyAlertSound).asString();
    }

    public void setCommandSpyAlertRegex(String commandSpyAlertRegex)
    {
        this.commandSpyAlertRegex = commandSpyAlertRegex == null || commandSpyAlertRegex.isEmpty() ? null : commandSpyAlertRegex;
        compileCommandSpyAlertRegex();
    }

    public String commandSpyAlertRegex()
    {
        return commandSpyAlertRegex;
    }

    public boolean commandSpyAlertMatches(String command)
    {
        if (commandSpyAlertPattern == null)
        {
            compileCommandSpyAlertRegex();
        }

        return commandSpyAlertPattern != null && commandSpyAlertPattern.matcher(command).find();
    }

    public boolean isSignSpy()
    {
        return getSignSpyMode() != SpyMode.OFF;
    }

    public SpyMode getSignSpyMode()
    {
        return signSpyMode == null ? SpyMode.OFF : signSpyMode;
    }

    public void setSignSpyMode(SpyMode signSpyMode)
    {
        this.signSpyMode = signSpyMode == null ? SpyMode.OFF : signSpyMode;
    }

    public boolean isBookSpy()
    {
        return getBookSpyMode() != SpyMode.OFF;
    }

    public SpyMode getBookSpyMode()
    {
        return bookSpyMode == null ? SpyMode.OFF : bookSpyMode;
    }

    public void setBookSpyMode(SpyMode bookSpyMode)
    {
        this.bookSpyMode = bookSpyMode == null ? SpyMode.OFF : bookSpyMode;
    }

    public boolean isJoinLeaveMessagesEnabled()
    {
        return joinLeaveMessagesEnabled;
    }

    public void setJoinLeaveMessagesEnabled(boolean joinLeaveMessagesEnabled)
    {
        this.joinLeaveMessagesEnabled = joinLeaveMessagesEnabled;
    }

    /**
     * The ids of every title this player holds. Unmodifiable: use {@link #addTitle(String)} and
     * {@link #removeTitle(String)} so ids stay normalised.
     */
    @Override
    public Set<String> getTitles()
    {
        return Collections.unmodifiableSet(titles);
    }

    /**
     * Grants a title. Returns {@code false} when it was already held.
     */
    public boolean addTitle(String titleId)
    {
        return titleId != null && titles.add(titleId.toLowerCase());
    }

    /**
     * Revokes a title. Returns {@code false} when it was not held.
     */
    public boolean removeTitle(String titleId)
    {
        return titleId != null && titles.remove(titleId.toLowerCase());
    }

    @Override
    public boolean hasTitle(String titleId)
    {
        return titleId != null && titles.contains(titleId.toLowerCase());
    }

    /**
     * Replaces the whole set, normalising ids. Used by the persistence layer on load.
     */
    public void setTitles(Collection<String> titleIds)
    {
        titles.clear();
        if (titleIds != null)
        {
            titleIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::toLowerCase)
                    .forEach(titles::add);
        }
    }

    /**
     * The stored address, ignoring the live session. {@link #getIp()} is the one to show a user;
     * this is the one identity checks compare against.
     */
    public Optional<String> getStoredIp()
    {
        return Optional.ofNullable(ip);
    }

    public void setIp(String ip)
    {
        this.ip = ip;
    }

    /**
     * The addresses the identity check accepts, least recently used first. Unmodifiable: use
     * {@link #trustIp(String)} and {@link #setTrustedIps(Collection)}.
     */
    public List<String> getTrustedIps()
    {
        return Collections.unmodifiableList(trustedIps);
    }

    public boolean isTrustedIp(String ip)
    {
        return ip != null && trustedIps.contains(ip);
    }

    /**
     * Marks {@code ip} as the most recently used trusted address, dropping the oldest once there
     * are more than {@link #MAX_TRUSTED_IPS}.
     */
    public void trustIp(String ip)
    {
        if (ip == null || ip.isBlank())
            return;

        trustedIps.remove(ip);
        trustedIps.add(ip);
        while (trustedIps.size() > MAX_TRUSTED_IPS)
            trustedIps.removeFirst();
    }

    /**
     * Replaces the whole list, oldest first. Only the newest {@link #MAX_TRUSTED_IPS} are kept.
     */
    public void setTrustedIps(Collection<String> ips)
    {
        trustedIps.clear();
        if (ips != null)
            ips.forEach(this::trustIp);
    }

    @Override
    public Optional<String> getRankId()
    {
        return Optional.ofNullable(rankId);
    }

    /**
     * Assigns a staff rank, or clears it with {@code null}. Assigning always reactivates.
     */
    public void setRankId(String rankId)
    {
        this.rankId = rankId == null ? null : rankId.toLowerCase();
        this.adminActive = true;
    }

    /**
     * Whether this player holds a staff rank on record, active or not.
     */
    public boolean isStaff()
    {
        return rankId != null;
    }

    /**
     * Whether this player holds a staff rank that currently applies.
     */
    public boolean isActiveStaff()
    {
        return rankId != null && adminActive;
    }

    public boolean isAdminActive()
    {
        return adminActive;
    }

    public void setAdminActive(boolean adminActive)
    {
        this.adminActive = adminActive;
    }

    public Optional<String> getLoginMessage()
    {
        return Optional.ofNullable(loginMessage).filter(message -> !message.isEmpty());
    }

    public void setLoginMessage(String loginMessage)
    {
        this.loginMessage = loginMessage;
    }

    @Override
    public Optional<String> getDiscordId()
    {
        return Optional.ofNullable(discordId);
    }

    public Optional<String> getDiscordIdentifier()
    {
        return Optional.ofNullable(discordIdentifier);
    }

    /**
     * Links a Discord account, or unlinks with {@code null}. Unlinking also drops the identifier.
     * The identifier itself is assigned by {@link PlayerList}, which can see every other one.
     */
    public void setDiscordId(String discordId)
    {
        this.discordId = discordId;
        if (discordId == null)
            this.discordIdentifier = null;
    }

    public void setDiscordIdentifier(String discordIdentifier)
    {
        this.discordIdentifier = discordIdentifier;
    }

    @Override
    public boolean isValid()
    {
        return uuid != null
                && username != null
                && firstJoinUnix != 0
                && lastJoinUnix != 0
                && ip != null;
    }

    public void setStrikes(int strikes)
    {
        if (strikes < 0 || strikes > MAX_STRIKES)
            return;
        this.strikes = strikes;
    }

    public void setNickname(Component nickname)
    {
        this.nickname = nickname;
        if (!hasCustomNickname())
            this.nickname = null;
        final Player player = Bukkit.getPlayerExact(username);
        if (player != null)
            player.displayName(hasCustomNickname() ? getDisplayedNickname() :
                    Component.text(player.getName(), player.isOp() ? NamedTextColor.RED : NamedTextColor.WHITE));
        PluginProvider.get().savePlayerData(this);
    }

    /**
     * Set the nickname without the display/save side effects {@link #setNickname} has.
     * Used when hydrating a PlayerData from storage (repository load), where the player
     * is not necessarily online and a save-after-load would be redundant.
     */
    public void setNicknameRaw(Component nickname)
    {
        this.nickname = nickname;
        if (!hasCustomNickname())
            this.nickname = null;
    }

    @Override
    public Component getDisplayedNickname()
    {
        if (!hasCustomNickname())
            return null;
        return Component.text("~", NamedTextColor.GRAY)
            .append(this.nickname.colorIfAbsent(NamedTextColor.WHITE));
    }

    @Override
    public boolean hasCustomNickname()
    {
        if (this.nickname == null)
            return false;
        final String plain = AdventureUtil.componentToPlainText(this.nickname).trim();
        if (!AdventureUtil.hasVisibleText(plain))
            return false;
        if (!plain.equalsIgnoreCase(username))
            return true;
        return AdventureUtil.hasVisualStyle(this.nickname);
    }

    public FreezeData getFreezeData()
    {
        return freezeData;
    }

    public CageData getCageData()
    {
        return cageData;
    }

    public ChatSpamData getChatSpamData()
    {
        return chatSpamData;
    }

    public double getFuckoffRadius()
    {
        return fuckoffRadius;
    }

    public void setFuckoff(double radius)
    {
        this.fuckoffRadius = radius;
    }

    public void disableFuckoff()
    {
        this.fuckoffRadius = 0;
    }

    public boolean isOrbiting()
    {
        return orbiting;
    }

    public void startOrbiting(double strength)
    {
        this.orbiting = true;
        this.orbitStrength = strength;
    }

    public void stopOrbiting()
    {
        this.orbiting = false;
    }

    public double orbitStrength()
    {
        return orbitStrength;
    }

    public int incrementAndGetMsgCount()
    {
        final long now = System.currentTimeMillis();
        if (now - messageCountWindowStart > MESSAGE_COUNTER_WINDOW_MS)
        {
            messageCount = 0;
            messageCountWindowStart = now;
        }
        return this.messageCount++;
    }

    public int incrementDropCount(long windowMs)
    {
        final long now = System.currentTimeMillis();
        if (now - dropCountWindowStart > windowMs)
        {
            dropCount = 0;
            dropCountWindowStart = now;
        }
        return this.dropCount++;
    }

    public void resetDropCount()
    {
        this.dropCount = 0;
        this.dropCountWindowStart = System.currentTimeMillis();
    }

    public int incrementDropItemCount(int amount, long windowMs)
    {
        final long now = System.currentTimeMillis();
        if (now - dropItemCountWindowStart > windowMs)
        {
            dropItemCount = 0;
            dropItemCountWindowStart = now;
        }
        final int before = this.dropItemCount;
        this.dropItemCount += Math.max(amount, 1);
        return before;
    }

    public void resetDropItemCount()
    {
        this.dropItemCount = 0;
        this.dropItemCountWindowStart = System.currentTimeMillis();
    }

    public int incrementAndGetBlockDestroyCount()
    {
        final long now = System.currentTimeMillis();
        if (now - totalBlockDestroyWindowStart > COUNTER_WINDOW_MS)
        {
            totalBlockDestroy = 0;
            totalBlockDestroyWindowStart = now;
        }
        return this.totalBlockDestroy++;
    }

    public void resetBlockDestroyCount()
    {
        this.totalBlockDestroy = 0;
        this.totalBlockDestroyWindowStart = System.currentTimeMillis();
    }

    public int incrementAndGetBlockPlaceCount()
    {
        final long now = System.currentTimeMillis();
        if (now - totalBlockPlaceWindowStart > COUNTER_WINDOW_MS)
        {
            totalBlockPlace = 0;
            totalBlockPlaceWindowStart = now;
        }
        return this.totalBlockPlace++;
    }

    public void resetBlockPlaceCount()
    {
        this.totalBlockPlace = 0;
        this.totalBlockPlaceWindowStart = System.currentTimeMillis();
    }

    public int incrementAndGetFreecamDestroyCount()
    {
        return this.freecamDestroyCount++;
    }

    public void resetFreecamDestroyCount()
    {
        this.freecamDestroyCount = 0;
    }

    public int incrementAndGetFreecamPlaceCount()
    {
        return this.freecamPlaceCount++;
    }

    public void resetFreecamPlaceCount()
    {
        this.freecamPlaceCount = 0;
    }

    public void startArrowShooter()
    {
        this.stopArrowShooter();
        final Player shooter = getPlayer();
        if (shooter == null)
            return;

        // Runs every tick for as long as the player holds fire, so an unguarded throw here
        // repeats until the task is cancelled.
        this.mp44ScheduleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () ->
        {
            if (!shooter.isOnline())
            {
                stopArrowShooter();
                return;
            }

            FTask.run("PlayerData/arrowShooter", () ->
            {
                final Arrow shot = shooter.launchProjectile(Arrow.class);
                shot.setVelocity(shot.getVelocity().multiply(2.0));
                shot.setShooter(player);
            });
        }, 1L, 1L);
        this.mp44Firing = true;
    }

    public void stopArrowShooter()
    {
        if (this.mp44ScheduleTask != null)
        {
            this.mp44ScheduleTask.cancel();
            this.mp44ScheduleTask = null;
        }
        this.mp44Firing = false;
    }

    public void armMP44()
    {
        this.mp44Armed = true;
        this.stopArrowShooter();
    }

    public void disarmMP44()
    {
        this.mp44Armed = false;
        this.stopArrowShooter();
    }

    public boolean isMP44Armed()
    {
        return this.mp44Armed;
    }

    public boolean toggleMP44Firing()
    {
        this.mp44Firing = !this.mp44Firing;
        return mp44Firing;
    }

    public BukkitTask getLockupScheduleID()
    {
        return this.lockupScheduleTask;
    }

    public void setLockupScheduleId(BukkitTask id)
    {
        this.lockupScheduleTask = id;
    }

    public void setAdminChat(boolean inAdminChat)
    {
        this.inAdminChat = inAdminChat;
    }

    public boolean inAdminChat()
    {
        return this.inAdminChat;
    }

    public void setTag(String tag)
    {
        this.tagInternal = tag;
        this.tag = AdventureUtil.format(tag);
    }

    public Component getTag()
    {
        return this.tag;
    }

    public String getInternalTag()
    {
        return this.tagInternal;
    }

    public void incrementWarnings()
    {
        this.warningCount++;

        if (this.warningCount % 2 == 0)
        {
            final Player p = getPlayer();
            p.getWorld().strikeLightning(p.getLocation());
            FUtil.playerMsg(p, String.format("You have been warned at least twice now, make sure to read the rules at %s",
                    ConfigEntry.SERVER_BAN_URL.getString()), NamedTextColor.RED);
        }
    }

    /**
     * Attaches this record to the running plugin. Called by {@link PlayerList} on every instance
     * it hands out; before this, setters don't schedule tasks or save.
     * <p>
     * Staff records written before trusted addresses were kept only have the one stored address,
     * so that one becomes the first trusted address.
     */
    void bind(FreedomAPI plugin)
    {
        this.plugin = plugin;
        if (isStaff() && trustedIps.isEmpty())
            trustIp(ip);
    }

    private static String randomDiscordIdentifier()
    {
        final StringBuilder identifier = new StringBuilder("#");
        DISCORD_IDENTIFIER_RANDOM.ints(DISCORD_IDENTIFIER_LENGTH, 0, DISCORD_IDENTIFIER_ALPHABET.length())
                                 .forEach(index -> identifier.append(DISCORD_IDENTIFIER_ALPHABET.charAt(index)));
        return identifier.toString();
    }

    private void compileCommandSpyAlertRegex()
    {
        if (commandSpyAlertRegex == null || commandSpyAlertRegex.isEmpty())
        {
            this.commandSpyAlertPattern = null;
            return;
        }

        try
        {
            this.commandSpyAlertPattern = Pattern.compile(commandSpyAlertRegex);
        }
        catch (IllegalArgumentException ex)
        {
            this.commandSpyAlertRegex = null;
            this.commandSpyAlertPattern = null;
        }
    }
}
