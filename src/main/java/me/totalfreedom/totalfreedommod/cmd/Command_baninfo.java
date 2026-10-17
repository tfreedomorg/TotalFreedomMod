package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "baninfo", description = "Show info about a ban by username or the first match to a (partial) IP.", usage = "/baninfo <name|ip>", aliases = {"checkban"})
@Permission(permission = "tfm.admin.baninfo")
public class Command_baninfo extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.banned(plugin(), partial);
    }

    @Callback
    public void query(CommandSender sender, String target)
    {
        final Optional<Ban> ban = Optional.ofNullable(plugin().bm.getByUsername(target))
                .or(() -> Optional.ofNullable(plugin().bm.getByIp(target)));

        if (ban.isPresent())
        {
            printBan(sender, ban.get());
            return;
        }

        final Optional<PermBan> permban = Optional.ofNullable(plugin().pm.getPermban(target))
                .or(() -> plugin().pm.getPermbannedNames().stream()
                        .map(plugin().pm::getPermban)
                        .filter(candidate -> candidate != null && candidate.getIps() != null && candidate.getIps().contains(target))
                        .findFirst());

        if (permban.isPresent())
        {
            printPermban(sender, permban.get());
            return;
        }

        if (isPartialIP(target))
        {
            final String normalized = normalizePartialIP(target);
            final int leading = countLeadingOctets(target);
            final AtomicBoolean foundAny = new AtomicBoolean(false);

            plugin().bm.getAllBans()
                .stream()
                .filter(candidate -> !candidate.isExpired())
                .forEach(candidate -> candidate.getIps()
                        .stream()
                        .filter(ip -> FUtil.fuzzyIpMatch(normalized, ip, leading))
                        .findFirst()
                        .ifPresent(ip ->
                            {
                                printBan(sender, candidate);
                                foundAny.set(true);
                            }));

            plugin().pm.getPermbannedNames()
                .forEach(name ->
                    {
                        final PermBan candidate = plugin().pm.getPermban(name);
                        if (candidate == null || candidate.getIps() == null) return; // in a lambda, return does NOT return the execution back to the caller. It simply moves to the next element in the set.
                        candidate.getIps()
                            .stream()
                            .filter(ip -> FUtil.fuzzyIpMatch(normalized, ip, leading))
                            .findFirst()
                            .ifPresent(ip ->
                                {
                                    printPermban(sender, candidate);
                                    foundAny.set(true);
                                });
                    });

            if (foundAny.get())
            {
                return;
            }
        }

        msg(sender, "<gray>No ban or permban found for: <white><target>", MessageUtils.unparsed("target", target));
    }

    private static boolean isPartialIP(String s)
    {
        String[] parts = s.split("\\.", -1);
        if (parts.length == 4 || s.isEmpty()) 
            return false;

        if (Stream.of(parts).allMatch(part -> !part.isEmpty() && (part.equals("*") || part.chars().allMatch(Character::isDigit)))) 
            return false;

        boolean hasWildcard = Stream.of(parts).anyMatch(part -> part.equals("*"));
        return hasWildcard || parts.length < 4;
    }

    private static String normalizePartialIP(String s) 
    {
        String[] parts = s.split("\\.", -1);
    
        return IntStream.range(0, 4)
                        .mapToObj(i -> i < parts.length ? parts[i] : "*")
                        .collect(Collectors.joining("."));
    }

    private static int countLeadingOctets(String s) 
    {
        String[] parts = s.split("\\.", -1);
    
        long count = Stream.of(parts)
                           .takeWhile(part -> !part.equals("*"))
                           .count();
        
        return (int) Math.min(count, 4);
    }

    private void printBan(CommandSender sender, Ban ban)
    {
        StringBuilder sb = new StringBuilder("<red>Ban: <white>");
        sb.append(ban.hasUsername() ? ban.getUsername() : "(IP-only)")
          .append("\n<gray> - Reason: <white>")
          .append(MessageUtils.toPlainText(FUtil.colorizeWithLinks(blankOr(ban.getReason(), "(none)"))))
          .append("\n<gray> - Banned by: <white>")
          .append(blankOr(ban.getBy(), "(none)"))
          .append("\n<gray> - IPs: <white>")
          .append(formatIps(sender, ban.getIps()))
          .append("\n<gray> - Expires: <white>");

        if (ban.hasExpiry())
            sb.append(Ban.DATE_FORMAT.format(ban.getExpiryDate()));
        else
            sb.append("<yellow>Unknown (legacy ban)");

        msg(sender, sb.toString());
    }

    private void printPermban(CommandSender sender, PermBan permban)
    {
        StringBuilder sb = new StringBuilder("<dark_red>Permban: <white>");
        sb.append(permban.getUsername() != null && !permban.getUsername().isEmpty() ? permban.getUsername() : "(IP-only)")
          .append("\n<gray> - Reason: <white>")
          .append(MessageUtils.toPlainText(FUtil.colorizeWithLinks(blankOr(permban.getReason(), "(none)"))))
          .append("\n<gray> - IPs: <white>")
          .append(formatIps(sender, permban.getIps()))
          .append("\n<gray> - Expires: <red>Permanent");

        msg(sender, sb.toString());
    }

    private String formatIps(CommandSender sender, List<String> ips)
    {
        if (ips == null || ips.isEmpty())
            return "(none)";

        return ips.stream()
                  .map(ip -> FUtil.sanitizeIp(sender, ip))
                  .collect(Collectors.joining(", "));
    }

    private static String blankOr(String value, String fallback)
    {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
