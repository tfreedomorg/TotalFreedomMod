package me.totalfreedom.totalfreedommod.player;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigLoadable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigSavable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.Validatable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class PlayerData implements ConfigLoadable, ConfigSavable, Validatable
{
    public static final int MAX_STRIKES = 3;
    // max number of IP addresses retained per player
    public static final int MAX_IPS = 2;

    @Getter
    @Setter
    private String username;
    @Getter
    @Setter
    private long firstJoinUnix;
    @Getter
    @Setter
    private long lastJoinUnix;
    @Getter
    @Setter
    private boolean potionSpy;
    private boolean signSpy;
    private CommandSpyMode commandSpyMode = CommandSpyMode.OFF;
    @Getter
    @Setter
    private boolean muted;
    @Getter
    @Setter
    private boolean frozen;
    @Getter
    @Setter
    private boolean commandsBlocked;
    private boolean joinLeaveMessagesEnabled = true;
    @Getter
    @Setter
    private String savedTag;
    @Getter
    private Component nickname;
    @Getter
    private int strikes;
    private final List<String> ips = Lists.newArrayList();

    public PlayerData(Player player)
    {
        this(player.getName());
    }

    public PlayerData(String username)
    {
        this.username = username;
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
        this.potionSpy = cs.getBoolean("potion_spy", false);
        this.signSpy = cs.getBoolean("sign_spy", false);
        final boolean legacyCommandSpy = cs.getBoolean("command_spy", false);
        this.commandSpyMode = CommandSpyMode.fromString(cs.getString("command_spy_mode", legacyCommandSpy ? "ops" : "off"));
        this.muted = cs.getBoolean("muted", false);
        this.frozen = cs.getBoolean("frozen", false);
        this.commandsBlocked = cs.getBoolean("commands_blocked", false);
        this.joinLeaveMessagesEnabled = cs.getBoolean("join_leave_messages", true);
        this.strikes = cs.getInt("strikes", 0);
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

    @Override
    public void saveTo(ConfigurationSection cs)
    {
        Preconditions.checkArgument(isValid(), "Could not save player entry: " + username + ". Entry not valid!");
        cs.set("username", username);
        cs.set("ips", ips);
        cs.set("first_join", firstJoinUnix);
        cs.set("last_join", lastJoinUnix);
        cs.set("potion_spy", potionSpy);
        cs.set("sign_spy", signSpy);
        cs.set("command_spy", isCommandSpy());
        cs.set("command_spy_mode", commandSpyMode.getName());
        cs.set("muted", muted);
        cs.set("frozen", frozen);
        cs.set("commands_blocked", commandsBlocked);
        cs.set("join_leave_messages", joinLeaveMessagesEnabled);
        cs.set("strikes", strikes);
        cs.set("saved_tag", savedTag);
        cs.set("nickname", nickname != null ? AdventureUtil.componentToLegacy(nickname) : null);
    }

    public boolean isCommandSpy()
    {
        return commandSpyMode != CommandSpyMode.OFF;
    }

    public void setCommandSpy(boolean commandSpy)
    {
        this.commandSpyMode = commandSpy ? CommandSpyMode.ALL : CommandSpyMode.OFF;
    }

    public CommandSpyMode getCommandSpyMode()
    {
        return commandSpyMode;
    }

    public void setCommandSpyMode(CommandSpyMode commandSpyMode)
    {
        this.commandSpyMode = commandSpyMode == null ? CommandSpyMode.OFF : commandSpyMode;
    }

    public boolean isSignSpy()
    {
        return signSpy;
    }

    public void setSignSpy(final boolean signSpy)
    {
        this.signSpy = signSpy;
    }

    public boolean isJoinLeaveMessagesEnabled()
    {
        return joinLeaveMessagesEnabled;
    }

    public void setJoinLeaveMessagesEnabled(boolean joinLeaveMessagesEnabled)
    {
        this.joinLeaveMessagesEnabled = joinLeaveMessagesEnabled;
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
        if (player != null && plugin != null && plugin.pbe != null)
            plugin.pbe.refreshPlayerIdentity(player);
        if (plugin != null && plugin.pl != null)
            plugin.pl.saveData(this);
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
