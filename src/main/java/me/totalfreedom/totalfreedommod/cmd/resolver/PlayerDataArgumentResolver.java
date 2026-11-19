package me.totalfreedom.totalfreedommod.cmd.resolver;

import me.totalfreedom.api.PluginProvider;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.resolver.AbstractArgumentResolver;
import me.totalfreedom.api.cmd.resolver.ArgumentResolutionException;
import me.totalfreedom.api.player.IPlayerList;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class PlayerDataArgumentResolver implements AbstractArgumentResolver<PlayerData>
{
    private PlayerData resolveDefault(String arg)
    {
        final IPlayerList api = PluginProvider.get().players();
        PlayerData data;

        // UUID
        try
        {
            final UUID uuid = UUID.fromString(arg);
            data = api.getData(uuid);
        }
        // Username
        catch (IllegalArgumentException ex)
        {
            data = api.getData(arg);
        }

        // Nickname
        if (data == null)
        {
            data = api.getData(Bukkit.getOnlinePlayers().stream()
                    .filter(p -> AdventureUtil.componentToPlainText(p.displayName()).toLowerCase().contains(arg.toLowerCase()))
                    .findAny()
                    .orElseThrow(() -> new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND)));
        }

        return data;
    }

    @Override
    public String name()
    {
        return "PlayerData";
    }

    @Override
    public List<String> suggestions()
    {
        return Bukkit.getOnlinePlayers().stream()
                     .map(Player::getName)
                     .sorted()
                     .toList();
    }

    @Override
    public PlayerData resolve(String arg, String strategy)
    {
        return resolveDefault(arg);
    }

}
