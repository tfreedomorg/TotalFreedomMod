package me.totalfreedom.totalfreedommod.httpd.module;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD;

public class Module_list extends HTTPDModule
{

    public Module_list(TotalFreedomMod plugin, NanoHTTPD.HTTPSession session)
    {
        super(plugin, session);
    }

    @Override
    public String getBody()
    {
        final StringBuilder body = new StringBuilder();

        final List<Player> onlinePlayers = Bukkit.getOnlinePlayers()
                                                   .stream()
                                                   .filter(player -> !plugin.vs.isVanished(player))
                                                   .map(player -> (Player) player)
                                                   .toList();

        body.append("<p>There are ").append(onlinePlayers.size()).append("/").append(Bukkit.getMaxPlayers()).append(" players online:</p>\r\n");

        body.append("<ul>\r\n");

        for (Player player : onlinePlayers)
        {
            String tag = plugin.rm.getDisplay(player).getTag();
            body.append("<li>").append(tag).append(player.getName()).append("</li>\r\n");
        }

        body.append("</ul>\r\n");

        return body.toString();
    }

    @Override
    public String getTitle()
    {
        return "Total Freedom - Online Users";
    }
}
