package me.totalfreedom.totalfreedommod.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;

public class FUtil
{

    private static final Random RANDOM = new Random();
    private static final Component separator = Component.text(" - ");
    // See https://github.com/TotalFreedom/License - None of the listed names may be removed.
    public static final List<String> DEVELOPERS = Arrays.asList("Madgeek1450", "Prozza", "Wild1145", "WickedGamingUK", "aggelosQQ", "aokod", "rptt", "ERR_666");
    public static String DATE_STORAGE_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";
    public static final Map<String, NamedTextColor> CHAT_COLOR_NAMES = new HashMap<>();
    public static final List<NamedTextColor> CHAT_COLOR_POOL = Arrays.asList(
            NamedTextColor.DARK_BLUE,
            NamedTextColor.DARK_GREEN,
            NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED,
            NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD,
            NamedTextColor.BLUE,
            NamedTextColor.GREEN,
            NamedTextColor.AQUA,
            NamedTextColor.RED,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.YELLOW);

    private FUtil()
    {
    }

    public static void cancel(BukkitTask task)
    {
        if (task == null)
        {
            return;
        }

        try
        {
            task.cancel();
        }
        catch (Exception ex)
        {
        }
    }

    public static void bcastMsg(Component component)
    {
        // Serialize to ANSI for console, Component for players
        String ansiMessage = ANSIComponentSerializer.ansi().serialize(component);
        Bukkit.getConsoleSender().sendMessage(ansiMessage);

        for (Player player : Bukkit.getOnlinePlayers())
        {
            player.sendMessage(component);
        }
    }

    @Deprecated
    public static void bcastMsg(String message, NamedTextColor color)
    {
        bcastMsg(colorizeWithLinks(message, color));
    }

    @Deprecated
    public static void bcastMsg(String message)
    {
        bcastMsg(colorizeWithLinks(message));
    }

    public static void bcastMsg(String miniMessage, TagResolver... resolvers)
    {
        bcastMsg(MessageUtils.parse(miniMessage, resolvers));
    }

    public static void playerMsg(CommandSender sender, Component component)
    {
        if (sender == null || component == null)
        {
            return;
        }
        sender.sendMessage(component);
    }

    public static void playerMsg(CommandSender sender, String message, NamedTextColor color)
    {
        playerMsg(sender, colorizeWithLinks(message, color));
    }

    public static void playerMsg(CommandSender sender, String message)
    {
        playerMsg(sender, message, NamedTextColor.GRAY);
    }

    public static void setFlying(Player player, boolean flying)
    {
        player.setAllowFlight(true);
        player.setFlying(flying);
    }

    @Deprecated
    public static void adminAction(String adminName, String action, boolean isRed)
    {
        FUtil.bcastMsg(adminName + " - " + action, (isRed ? NamedTextColor.RED : NamedTextColor.AQUA));
    }

    @Deprecated
    public static void adminAction(String adminName, Component action, NamedTextColor color)
    {
        FUtil.bcastMsg(Component.text(adminName, color)
                .append(Component.text(" - "))
                .append(action));
    }

    public static void adminAction(CommandSender sender, Component action)
    {
        final Component name = sender.name().color(action.color());
        
        FUtil.bcastMsg(name.append(separator.color(action.color()).append(action)));
    }

    public static String formatLocation(Location location)
    {
        return String.format("%s: (%d, %d, %d)",
                location.getWorld().getName(),
                Math.round(location.getX()),
                Math.round(location.getY()),
                Math.round(location.getZ()));
    }

    public static boolean deleteFolder(final File file)
    {
        if (file.exists() && file.isDirectory())
        {
            return FileUtils.deleteQuietly(file);
        }
        return false;
    }

    public static void deleteCoreDumps()
    {
        final File[] coreDumps = new File(".").listFiles(file -> file.getName().startsWith("java.core"));

        for (File dump : coreDumps)
        {
            FLog.info("Removing core dump file: " + dump.getName());
            dump.delete();
        }
    }

    /**
     * A single {@code <number><unit>} part of a duration. {@code mo} is listed before the
     * single-letter units so months win over minutes on the shared {@code m}.
     */
    private static final Pattern DURATION_PART = Pattern.compile("([0-9]+)(mo|[smhdwy])", Pattern.CASE_INSENSITIVE);

    /**
     * Parse a duration written in {@code <number><unit>} notation into an absolute future date.
     * <p>
     * Units are {@code s}econds, {@code m}inutes, {@code h}ours, {@code d}ays, {@code w}eeks,
     * {@code mo}nths and {@code y}ears. Parts may be chained in any order and are summed, so
     * {@code 5m}, {@code 1h30m} and {@code 2d7h45m31s} are all valid. Units are case-insensitive.
     * <p>
     * The offset must be a single unbroken token. {@code "1h 30m"} is not accepted, and could
     * not reach here anyway: custom-resolved arguments are Brigadier {@code word()} nodes, which
     * end at the first space.
     * <p>
     * Strict by design: the entire string must be number/unit pairs and the total must be
     * non-zero. Anything else returns {@code null} rather than a silently wrong date.
     *
     * @param time the offset to parse, e.g. {@code "30s"}, {@code "2h"}, {@code "1h30m"}
     * @return the resulting date, or {@code null} if {@code time} is not a valid offset
     */
    public static Date parseDateOffset(String time)
    {
        if (time == null || time.isEmpty())
        {
            return null;
        }

        final Matcher m = DURATION_PART.matcher(time);
        final Calendar c = new GregorianCalendar();
        int consumed = 0;
        long total = 0;

        while (m.find())
        {
            // Every part must butt up against the last, so stray text cannot be skipped over.
            if (m.start() != consumed)
            {
                return null;
            }
            consumed = m.end();

            final int value = Integer.parseInt(m.group(1));
            total += value;

            switch (m.group(2).toLowerCase(Locale.ENGLISH))
            {
                case "y" -> c.add(Calendar.YEAR, value);
                case "mo" -> c.add(Calendar.MONTH, value);
                case "w" -> c.add(Calendar.WEEK_OF_YEAR, value);
                case "d" -> c.add(Calendar.DAY_OF_MONTH, value);
                case "h" -> c.add(Calendar.HOUR_OF_DAY, value);
                case "m" -> c.add(Calendar.MINUTE, value);
                default -> c.add(Calendar.SECOND, value);
            }
        }

        // Reject trailing junk ("1h30") and offsets that do not move ("0m").
        if (consumed != time.length() || total == 0)
        {
            return null;
        }

        return c.getTime();
    }

    public static String playerListToNames(Set<OfflinePlayer> players)
    {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer player : players)
        {
            names.add(player.getName());
        }
        return String.join(", ", names);
    }

    public static String dateToString(Date date)
    {
        return new SimpleDateFormat(DATE_STORAGE_FORMAT, Locale.ENGLISH).format(date);
    }

    public static Date stringToDate(String dateString)
    {
        try
        {
            return new SimpleDateFormat(DATE_STORAGE_FORMAT, Locale.ENGLISH).parse(dateString);
        }
        catch (ParseException pex)
        {
            return new Date(0L);
        }
    }

    /**
     * The resolution at which the databases store timestamps. SQLite and MySQL both keep whole seconds,
     * so anything finer than this is an artifact of truncation rather than a real difference.
     */
    private static final long TIMESTAMP_RESOLUTION_MS = 1000L;

    /**
     * Whether a JSON snapshot should be treated as newer than the database it mirrors.
     * <p>
     * A write-through save writes its row and then its snapshot, so the file is always a few
     * milliseconds later than the row, and the stored timestamp is truncated to the second on
     * top of that. Comparing them directly therefore reports "newer" after almost every save,
     * which would rebuild the whole domain on each startup. Only a gap wider than the storage
     * resolution means the file was actually edited or restored behind the plugin's back.
     */
    public static boolean isSnapshotNewer(final long fileModified, final Long sqlUpdatedAt)
    {
        return sqlUpdatedAt == null || fileModified > sqlUpdatedAt + TIMESTAMP_RESOLUTION_MS;
    }

    public static boolean fuzzyIpMatch(String a, String b, int octets)
    {
        boolean match = true;

        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");

        if (aParts.length != 4 || bParts.length != 4)
        {
            return false;
        }

        if (octets > 4)
        {
            octets = 4;
        }
        else if (octets < 1)
        {
            octets = 1;
        }

        for (int i = 0; i < octets && i < 4; i++)
        {
            if (aParts[i].equals("*") || bParts[i].equals("*"))
            {
                continue;
            }

            if (!aParts[i].equals(bParts[i]))
            {
                match = false;
                break;
            }
        }

        return match;
    }

    public static String getFuzzyIp(String ip)
    {
        final String[] ipParts = ip.split("\\.");
        if (ipParts.length == 4)
        {
            return String.format("%s.%s.*.*", ipParts[0], ipParts[1]);
        }

        return ip;
    }

    /**
     * Returns the IP masked to the form "192.168.*.*" when {@code mask_ips} is enabled
     * in config and the sender lacks {@code tfm.manage.showips}, otherwise returns IP unchanged.
     */
    public static String sanitizeIp(CommandSender sender, String ip)
    {
        if (ip == null || ip.isEmpty())
        {
            return ip;
        }
        if (!ConfigEntry.MASK_IPS.getBoolean())
        {
            return ip;
        }
        if (sender != null && PluginProvider.get().rm != null
                && PluginProvider.get().rm.hasPermission(sender, "tfm.manage.showips"))
        {
            return ip;
        }
        return getFuzzyIp(ip);
    }

    //getField: Borrowed from WorldEdit
    @SuppressWarnings("unchecked")
    public static <T> T getField(Object from, String name)
    {
        Class<?> checkClass = from.getClass();
        do
        {
            try
            {
                Field field = checkClass.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(from);

            }
            catch (NoSuchFieldException | IllegalAccessException ex)
            {
            }
        } while (checkClass.getSuperclass() != Object.class
                && ((checkClass = checkClass.getSuperclass()) != null));

        return null;
    }

    /**
    * Interpolates between two points.
    */
    public static final double lerp(final double progress, final double from, final double to)
    {
        return from * (1.0D - progress) + to * progress;
    }

    public static NamedTextColor randomChatColor()
    {
        return CHAT_COLOR_POOL.get(RANDOM.nextInt(CHAT_COLOR_POOL.size()));
    }

    public static Component colorize(String string)
    {
        return AdventureUtil.format(string);
    }

    public static Component colorize(String string, NamedTextColor defaultColor)
    {
        Component component = colorize(string);
        return defaultColor == null ? component : component.colorIfAbsent(defaultColor);
    }

    public static Component colorizeWithLinks(String string)
    {
        return AdventureUtil.addLinks(colorize(string));
    }

    public static Component colorizeWithLinks(String string, NamedTextColor defaultColor)
    {
        return AdventureUtil.addLinks(colorize(string, defaultColor));
    }

    public static Date getUnixDate(long unix)
    {
        return new Date(unix * 1000);
    }

    public static long getUnixTime()
    {
        return System.currentTimeMillis() / 1000L;
    }

    public static long getUnixTime(Date date)
    {
        if (date == null)
        {
            return 0;
        }

        return date.getTime() / 1000L;
    }

    public static String getNmsVersion()
    {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }

    // ============================================
    // UUID Utilities
    // ============================================

    private static final String MOJANG_API_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final Map<String, UUID> UUID_CACHE = new HashMap<>();

    /**
     * Converts a username to a UUID: online players first, then Mojang HTTP if {@code adminlist.mojang_uuid_lookup} is true.
     *
     * @param username The player's username
     * @return The player's UUID, or null if not found, Mojang lookup disabled, or an error occurred
     */
    public static UUID usernameToUuid(String username)
    {
        if (username == null || username.isEmpty())
        {
            return null;
        }

        // Check cache first
        String lowerName = username.toLowerCase();
        if (UUID_CACHE.containsKey(lowerName))
        {
            return UUID_CACHE.get(lowerName);
        }

        // Check if player is online
        Player onlinePlayer = Bukkit.getPlayerExact(username);
        if (onlinePlayer != null)
        {
            UUID_CACHE.put(lowerName, onlinePlayer.getUniqueId());
            return onlinePlayer.getUniqueId();
        }

        Boolean mojangLookup = ConfigEntry.ADMINLIST_MOJANG_UUID_LOOKUP.getBoolean();
        if (Boolean.FALSE.equals(mojangLookup))
        {
            return null;
        }

        try
        {
            URL url = new URI(MOJANG_API_URL + username).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200)
            {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
                reader.close();

                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                String uuidString = json.get("id").getAsString();
                UUID uuid = parseUuidFromMojangFormat(uuidString);
                UUID_CACHE.put(lowerName, uuid);
                return uuid;
            }
            else if (responseCode == 204 || responseCode == 404)
            {
                // Player not found
                return null;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Failed to fetch UUID for " + username + ": " + ex.getMessage());
        }

        return null;
    }

    /**
     * Parses a UUID from Mojang's format (without dashes) to a standard UUID.
     *
     * @param mojangUuid The UUID string without dashes (32 characters)
     * @return The parsed UUID
     */
    public static UUID parseUuidFromMojangFormat(String mojangUuid)
    {
        if (mojangUuid == null || mojangUuid.length() != 32)
        {
            throw new IllegalArgumentException("Invalid Mojang UUID format: " + mojangUuid);
        }

        String formatted = mojangUuid.substring(0, 8) + "-"
                + mojangUuid.substring(8, 12) + "-"
                + mojangUuid.substring(12, 16) + "-"
                + mojangUuid.substring(16, 20) + "-"
                + mojangUuid.substring(20, 32);

        return UUID.fromString(formatted);
    }

    /**
     * Safely parses a UUID from a string, handling both standard and Mojang formats.
     *
     * @param uuidString The UUID string (with or without dashes)
     * @return The parsed UUID, or null if invalid
     */
    public static UUID parseUuid(String uuidString)
    {
        if (uuidString == null || uuidString.isEmpty())
        {
            return null;
        }

        try
        {
            // Standard format with dashes
            if (uuidString.contains("-"))
            {
                return UUID.fromString(uuidString);
            }
            // Mojang format without dashes
            else if (uuidString.length() == 32)
            {
                return parseUuidFromMojangFormat(uuidString);
            }
        }
        catch (IllegalArgumentException ex)
        {
            FLog.warning("Failed to parse UUID: " + uuidString);
        }

        return null;
    }

    /**
     * Converts a UUID to Mojang's format (without dashes).
     *
     * @param uuid The UUID to convert
     * @return The UUID string without dashes
     */
    public static String uuidToMojangFormat(UUID uuid)
    {
        if (uuid == null)
        {
            return null;
        }
        return uuid.toString().replace("-", "");
    }

    /**
     * Clears the UUID cache. Useful for testing or when cache needs to be refreshed.
     */
    public static void clearUuidCache()
    {
        UUID_CACHE.clear();
    }

    public static Object stringToObject(String input)
    {
        // Numbers
        if (input.toLowerCase().matches("[0-9]+[ls]?"))
        {
            try
            {
                // Expect long
                if (input.toLowerCase().endsWith("l"))
                {
                    return Long.parseLong(input.substring(0, input.length() - 1));
                }
                // Expect short
                else if (input.toLowerCase().endsWith("s"))
                {
                    return Short.parseShort(input);
                }

                // Expect integer
                return Integer.parseInt(input);
            }
            catch (NumberFormatException _)
            {
                // Fail quietly, we'll just make it a string instead
            }
        }

        // Booleans
        if (input.equalsIgnoreCase("true") || input.equalsIgnoreCase("false"))
        {
            return Boolean.parseBoolean(input);
        }

        return input;
    }
}
