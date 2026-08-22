package me.totalfreedom.totalfreedommod.world.profile;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Reads the .json files in the data folder's worlds directory. A file's name is the world's name,
 * and every file in there is a world we manage.
 * <p>
 * {@link #copyTemplate} is the only way a template would ever reach the disk,
 * and once it does it stops being a template and becomes that world's profile.
 * <p>
 * Only reads and parses JSON, so it is safe off the main thread. Turning that JSON into a profile
 * is {@link ProfileParser}, which is not.
 * <p>
 * Every world name passed in is checked against {@link #VALID_WORLD_NAME} before it touches a
 * {@link File}, so nothing here trusts a caller to have already constrained it. A world name with a
 * path separator or ".." would otherwise be able to read or write outside the worlds directory.
 */
public final class ProfileLoader
{
    private static final String WORLDS_DIRECTORY = "worlds";
    private static final String BIOMES_DIRECTORY = "biomes";
    private static final String JSON_EXTENSION = ".json";

    /** No path separators or "..", so a world name can never resolve outside {@link #directory}. */
    private static final Pattern VALID_WORLD_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final TotalFreedomMod plugin;
    private final File directory;
    private final File biomeDirectory;

    public ProfileLoader(final TotalFreedomMod plugin)
    {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), WORLDS_DIRECTORY);
        this.biomeDirectory = new File(this.directory, BIOMES_DIRECTORY);
    }

    /** Every world with a profile file on disk. */
    public Set<String> available()
    {
        return namesOf(this.directory);
    }

    /**
     * One world's raw JSON, off disk. Empty if it has no file, which is not an error.
     *
     * @throws ProfileException         if the file exists but is not readable JSON
     * @throws IllegalArgumentException if worldName is not a valid world name; callers taking a
     *                                  world name from an admin or a command must validate it
     *                                  before it reaches here, since this is the boundary that
     *                                  decides which file on disk gets touched
     */
    public Optional<JsonObject> read(final String worldName) throws ProfileException
    {
        requireValidWorldName(worldName);
        final File file = new File(this.directory, worldName + JSON_EXTENSION);

        if (!file.isFile())
            return Optional.empty();

        return Optional.of(readDisk(file, worldName));
    }

    /**
     * Every biome definition a profile can reference by name, bundled defaults first, then this
     * server's own {@code worlds/biomes} directory overlaid on top of any same-named bundled one.
     *
     * @throws ProfileException if a file on disk exists but is not readable JSON; a broken bundled
     *                          file is a packaging bug, not something an admin can fix, so it is
     *                          logged and skipped instead
     */
    public Map<String, JsonObject> biomeLibrary() throws ProfileException
    {
        final Map<String, JsonObject> library = new HashMap<>(readBundled(WORLDS_DIRECTORY + "/" + BIOMES_DIRECTORY));
        final File[] files = this.biomeDirectory.listFiles((dir, name) -> name.endsWith(JSON_EXTENSION));

        if (files == null)
            return library;

        for (final File file : files)
        {
            if (!file.isFile())
                continue;

            library.put(stripExtension(file.getName()), readDisk(file, BIOMES_DIRECTORY + "/" + file.getName()));
        }

        return library;
    }

    /** Names of the templates bundled in the jar. Never worlds. */
    public Set<String> templates()
    {
        return readBundled(WORLDS_DIRECTORY).keySet();
    }

    /**
     * Writes a bundled template out as a new world's profile. This is what creates a managed world,
     * so refuse if a file for that world already exists rather than overwriting someone's edits.
     *
     * @param templateName one of {@link #templates()}
     * @param worldName    the world to create, which becomes the file name
     * @throws IllegalArgumentException if worldName is not a valid world name
     */
    public boolean copyTemplate(final String templateName, final String worldName)
    {
        requireValidWorldName(worldName);
        final File target = new File(this.directory, worldName + JSON_EXTENSION);

        if (target.exists())
            return false;

        final String resourcePath = WORLDS_DIRECTORY + "/" + templateName + JSON_EXTENSION;

        try (final InputStream in = this.plugin.getResource(resourcePath))
        {
            if (in == null)
                return false;

            this.directory.mkdirs();
            Files.copy(in, target.toPath());
            return true;
        }
        catch (final IOException ex)
        {
            FLog.warning("Failed to copy template '" + templateName + "' to world '" + worldName + "': " + ex.getMessage());
            return false;
        }
    }

    /** @throws IllegalArgumentException if worldName contains anything but letters, digits, underscores, or hyphens */
    private static void requireValidWorldName(final String worldName)
    {
        if (worldName == null || !VALID_WORLD_NAME.matcher(worldName).matches())
            throw new IllegalArgumentException("invalid world name \"" + worldName + "\"");
    }

    /** Direct .json children of a data-folder directory, extension stripped. Never recurses. */
    private static Set<String> namesOf(final File directory)
    {
        final File[] files = directory.listFiles((dir, name) -> name.endsWith(JSON_EXTENSION));

        if (files == null)
            return Set.of();

        return Arrays.stream(files)
                     .filter(File::isFile)
                     .map(file -> stripExtension(file.getName()))
                     .collect(Collectors.toUnmodifiableSet());
    }

    /** One disk file, parsed. path is where the resulting ProfileError points if it fails. */
    private static JsonObject readDisk(final File file, final String path) throws ProfileException
    {
        try (final Reader reader = new FileReader(file))
        {
            final JsonElement root = JsonParser.parseReader(reader);

            if (!root.isJsonObject())
                throw new ProfileException(path, List.of(new ProfileError(path, "expected an object")));

            return root.getAsJsonObject();
        }
        catch (final IOException | JsonSyntaxException ex)
        {
            throw new ProfileException(path, List.of(new ProfileError(path, ex.getMessage())));
        }
    }

    /**
     * Every direct .json child of one directory bundled in the plugin jar, keyed by filename with
     * the extension stripped. Never recurses, so walking "worlds" never picks up "worlds/biomes".
     * A file that fails to parse is logged and skipped rather than failing the whole walk, since a
     * broken bundled resource is a packaging bug an admin cannot fix by editing anything on disk.
     */
    private Map<String, JsonObject> readBundled(final String jarPath)
    {
        final Map<String, JsonObject> result = new HashMap<>();

        try (final FileSystem zipFs = FileSystems.newFileSystem(Path.of(this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
             final Stream<Path> walk = Files.walk(zipFs.getPath("/" + jarPath), 1))
        {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(JSON_EXTENSION))
                .forEach(path ->
                {
                    try (final Reader reader = Files.newBufferedReader(path))
                    {
                        result.put(stripExtension(path.getFileName().toString()), JsonParser.parseReader(reader).getAsJsonObject());
                    }
                    catch (final IOException | JsonSyntaxException | IllegalStateException ex)
                    {
                        FLog.warning("Failed to read bundled resource " + path + ": " + ex.getMessage());
                    }
                });
        }
        catch (final IOException | URISyntaxException ex)
        {
            FLog.warning("Failed to walk bundled " + jarPath + " resources: " + ex.getMessage());
        }

        return result;
    }

    private static String stripExtension(final String fileName)
    {
        return fileName.substring(0, fileName.length() - JSON_EXTENSION.length());
    }
}
