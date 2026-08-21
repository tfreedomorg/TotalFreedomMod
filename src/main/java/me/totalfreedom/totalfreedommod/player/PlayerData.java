package me.totalfreedom.totalfreedommod.player;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigLoadable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.Validatable;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class PlayerData implements ConfigLoadable, Validatable
{
    public static final int MAX_STRIKES = 3;
    // max number of IP addresses retained per player
    public static final int MAX_IPS = 2;

    private String username;
    private long firstJoinUnix;
    private long lastJoinUnix;
    private SpyMode potionSpyMode = SpyMode.OFF;
    private SpyMode signSpyMode = SpyMode.OFF;
    private SpyMode bookSpyMode = SpyMode.OFF;
    private SpyMode commandSpyMode = SpyMode.OFF;
    private boolean muted;
    private boolean frozen;
    private boolean commandsBlocked;
    private boolean joinLeaveMessagesEnabled = true;
    private String savedTag;
    private Component nickname;
    private int strikes;
    private final List<String> ips = Lists.newArrayList();

    /**
     * Ids of the titles this player holds.
     * <p>
     * A player may hold several at once; their capabilities are the union of what those titles
     * grant, and no title implies any other. Stored as ids rather than as resolved titles so that
     * deleting a title simply stops it applying instead of stranding every holder.
     */
    private final Set<String> titles = Sets.newLinkedHashSet();

    public PlayerData(Player player)
    {
        this(player.getName());
    }

    public PlayerData(String username)
    {
        this.username = username;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public long getFirstJoinUnix()
    {
        return firstJoinUnix;
    }

    public void setFirstJoinUnix(long firstJoinUnix)
    {
        this.firstJoinUnix = firstJoinUnix;
    }

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

    public boolean isMuted()
    {
        return muted;
    }

    public void setMuted(boolean muted)
    {
        this.muted = muted;
    }

    public boolean isFrozen()
    {
        return frozen;
    }

    public void setFrozen(boolean frozen)
    {
        this.frozen = frozen;
    }

    public boolean isCommandsBlocked()
    {
        return commandsBlocked;
    }

    public void setCommandsBlocked(boolean commandsBlocked)
    {
        this.commandsBlocked = commandsBlocked;
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

    public int getStrikes()
    {
        return strikes;
    }

    @Override
    public void loadFrom(ConfigurationSection cs)
    {
        this.username = cs.getString("username", username);
        this.ips.clear();
        this.ips.addAll(cs.getStringList("ips"));
        trimIps();
        this.firstJoinUnix = cs.getLong("first_join", 0);
        this.lastJoinUnix = cs.getLong("last_join", 0);
        this.potionSpyMode = SpyMode.fromStorage(cs.getString("potion_spy_mode", cs.getBoolean("potion_spy", false) ? "all" : "off"));
        this.signSpyMode = SpyMode.fromStorage(cs.getString("sign_spy_mode", cs.getBoolean("sign_spy", false) ? "all" : "off"));
        this.bookSpyMode = SpyMode.fromStorage(cs.getString("book_spy_mode", "off"));
        final boolean legacyCommandSpy = cs.getBoolean("command_spy", false);
        this.commandSpyMode = SpyMode.fromStorage(cs.getString("command_spy_mode", legacyCommandSpy ? "ops" : "off"));
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

    public List<String> getIps()
    {
        return Collections.unmodifiableList(ips);
    }

    // IP utils
    public boolean addIp(String ip)
    {
        final boolean isNew = !ips.remove(ip);
        ips.add(ip);
        trimIps();
        return isNew;
    }

    private void trimIps()
    {
        while (ips.size() > MAX_IPS)
        {
            ips.remove(0);
        }
    }

    public boolean removeIp(String ip)
    {
        return ips.remove(ip);
    }

    @Override
    public boolean isValid()
    {
        return username != null
                && firstJoinUnix != 0
                && lastJoinUnix != 0
                && !ips.isEmpty();
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
        final TotalFreedomMod plugin = PluginProvider.get();
        if (plugin != null && plugin.pl != null)
            plugin.pl.saveData(this);
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

    public Component getDisplayedNickname()
    {
        if (!hasCustomNickname())
            return null;
        return Component.text("~", NamedTextColor.GRAY)
            .append(this.nickname.colorIfAbsent(NamedTextColor.WHITE));
    }

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
}
