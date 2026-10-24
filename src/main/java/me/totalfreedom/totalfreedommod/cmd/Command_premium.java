package me.totalfreedom.totalfreedommod.cmd;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FLog;

@Permission(source = SourceType.BOTH, permission = "tfm.admin.premium")
@Command(name = "premium", description = "Validates if a given account is premium.", usage = "/premium <player>", aliases = "prem")
public class Command_premium extends FCommand
{

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                                                            .connectTimeout(Duration.ofSeconds(5))
                                                            .build();

    @Completer(value = "", position = 0)
    public List<String> completeUsername(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Callback
    public void checkPremium(CommandSender sender, String username)
    {
        final Player player = getPlayer(username);
        final String name = player == null ? username : player.getName();

        if (!name.matches("^[A-Za-z0-9_]{3,16}$"))
        {
            msg(sender, "<red>That is not a valid Minecraft username.");
            return;
        }

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "TotalFreedomMod")
                .GET()
                .build();

        msg(
            sender, 
            "<gray>Checking the Minecraft account for <yellow><name></yellow>...",
            MessageUtils.unparsed("name", name)
        );

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                   .whenComplete((response, throwable) ->
                    {
                        if (!plugin().isEnabled())
                        {
                            return;
                        }

                        server().getScheduler().runTask(plugin(), () ->
                        {
                            if (throwable != null)
                            {
                                FLog.severe(throwable);
                                msg(sender, "<red>There was an error querying Minecraft Services.");
                                return;
                            }

                            switch (response.statusCode())
                            {
                                case 200 -> msgPremiumResult(sender, name, true);
                                case 204, 404 -> msgPremiumResult(sender, name, false);
                                case 429 -> msg(sender,
                                        "<red>Minecraft Services is currently rate limiting requests. Try again shortly.");
                                default -> msg(
                                                sender,
                                                "<red>Minecraft Services returned an unexpected response: HTTP <response>",
                                                Formatter.number("response", response.statusCode()) 
                                            );
                            }
                        });
                    });
    }

    private void msgPremiumResult(CommandSender sender, String name, boolean premium)
    {
        msg(
            sender, 
            "<gray>Player <yellow><name></yellow> is premium: <premium:Yes:No>",
            Placeholder.unparsed("name", name),
            Formatter.booleanChoice("premium", premium)
        );
    }
}