package me.totalfreedom.totalfreedommod;

import java.util.List;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FSync;

public class Muter extends FreedomService
{

    public static final List<String> MUTE_COMMANDS = List.of("say", "me", "msg", "tell", "reply", "mail", ",");

    public Muter(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAsyncPlayerChatEvent(AsyncChatEvent event)
    {
        if (shouldCancelChat(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW)
    public void onLegacyAsyncPlayerChat(AsyncPlayerChatEvent event)
    {
        if (shouldCancelChat(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    private boolean shouldCancelChat(Player player)
    {
        FPlayer fPlayer = plugin.pl.getPlayerSync(player);

        if (!fPlayer.isMuted())
        {
            return false;
        }

        if (plugin.al.isAdminSync(player))
        {
            fPlayer.setMuted(false);
            return false;
        }

        FSync.playerMsg(player, "You are muted, STFU! - You will be unmuted in 5 minutes.");
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {

        Player player = event.getPlayer();
        FPlayer fPlayer = plugin.pl.getPlayer(event.getPlayer());

        // Block commands if player is muted
        if (!fPlayer.isMuted())
        {
            return;
        }

        String message = event.getMessage();
        if (plugin.al.isAdmin(player))
        {
            fPlayer.setMuted(false);
            return;
        }

        String cmdName = message.split(" ")[0].toLowerCase();
        if (cmdName.startsWith("/"))
        {
            cmdName = cmdName.substring(1);
        }

        Command command = server.getCommandMap().getCommand(cmdName);
        if (command != null)
        {
            cmdName = command.getName().toLowerCase();
        }

        if (MUTE_COMMANDS.contains(cmdName))
        {
            player.sendMessage(Component.text("That command is blocked while you are muted.", NamedTextColor.RED));
            event.setCancelled(true);
        }
    }
}
