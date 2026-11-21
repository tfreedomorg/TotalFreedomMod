package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.internal.ResolverRegistry;
import me.totalfreedom.totalfreedommod.player.PlayerData;

import com.google.common.net.InetAddresses;

public class InetAddressListResolver implements AbstractParameterizedArgumentResolver<List<InetAddress>>
{
    @Override
    public String name()
    {
        return "IPs";
    }

    @Override
    public List<InetAddress> resolve(String arg, Map<String, Object> parameters)
    {
        final boolean playerFallback = (boolean) parameters.getOrDefault("allowPlayers", false);
        final boolean all = (boolean) parameters.getOrDefault("all", false);
        final long limit = (long) parameters.getOrDefault("limit", Long.MAX_VALUE);

        // Try as an IP address first
        if (InetAddresses.isInetAddress(arg))
        {
            return List.of(InetAddress.ofLiteral(arg));
        }

        // Bomb out with an error if we aren't allowed to fallback onto regular Players
        if (!playerFallback)
        {
            throw new ArgumentResolutionException("Invalid IP address: " + arg);
        }

        // Fallback onto regular players, getting all of their IPs get all of their IPs
        final Player player = (Player) ResolverRegistry.byName("Player").resolve(arg, null);

        // Get all their IP addresses
        if (all)
        {
            final PlayerData playerData = PluginProvider.get().pl.getData(player);
            return playerData.getIps().stream()
                    .filter(InetAddresses::isInetAddress)
                    .map(InetAddress::ofLiteral)
                    .limit(limit)
                    .toList();
        }
        // ...otherwise, just use their current IP.
        else
        {
            return List.of(Objects.requireNonNull(player.getAddress()).getAddress());
        }
    }
}
