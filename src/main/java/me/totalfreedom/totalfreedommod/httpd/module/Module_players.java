package me.totalfreedom.totalfreedommod.httpd.module;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class Module_players extends HTTPDModule
{

    public Module_players(TotalFreedomMod plugin, NanoHTTPD.HTTPSession session)
    {
        super(plugin, session);
    }

    @Override
    public NanoHTTPD.Response getResponse()
    {
        final JsonObject responseObject = new JsonObject();

        final JsonArray players = new JsonArray();
        final JsonArray onlineadmins = new JsonArray();
        final JsonArray superadmins = new JsonArray();
        final JsonArray senioradmins = new JsonArray();
        final JsonArray developers = new JsonArray();

        // All online players, excluding anyone vanished - this endpoint has no authenticated
        // viewer to check a bypass permission against.
        for (Player player : Bukkit.getOnlinePlayers())
        {
            if (plugin.vs.isVanished(player))
            {
                continue;
            }

            players.add(player.getName());
            if (plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
            {
                onlineadmins.add(player.getName());
            }
        }

        // Admins
        for (Admin admin : plugin.al.getActiveAdmins())
        {
            final String username = admin.getName();
            
            if (plugin.al.grantsSeniorStatus(admin))
            {
                senioradmins.add(username);
            }
            else
            {
                superadmins.add(username);
            }
        }

        // Developers
        for (String developer : FUtil.DEVELOPERS)
        {
            developers.add(developer);
        }

        responseObject.add("players", players);
        responseObject.add("onlineadmins", onlineadmins);
        responseObject.add("superadmins", superadmins);
        responseObject.add("senioradmins", senioradmins);
        responseObject.add("developers", developers);

        final NanoHTTPD.Response response = new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_JSON, responseObject.toString());
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }
}
