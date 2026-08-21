package me.totalfreedom.totalfreedommod.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bukkit.Bukkit;
import org.bukkit.generator.ChunkGenerator;

import com.google.gson.JsonObject;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.world.adapter.ProfileChunkGenerator;
import me.totalfreedom.totalfreedommod.world.profile.ProfileException;
import me.totalfreedom.totalfreedommod.world.profile.ProfileLoader;
import me.totalfreedom.totalfreedommod.world.profile.ProfileParser;
import me.totalfreedom.totalfreedommod.world.profile.WorldSettings;

/**
 * Holds the profile registry. Files are read and parsed at startup, and only worlds that parsed
 * cleanly end up in here.
 * <p>
 * A profile that fails is logged with every problem found and then skipped, so one bad file costs
 * you that world and nothing else. The rest of the plugin does not care that world generation had a
 * bad day.
 * <p>
 * Sits behind the plugin's getDefaultWorldGenerator hook, which is what lets a profile drive a
 * world created through bukkit.yml or a world manager instead of only ones we create ourselves.
 */
public final class GenerationService extends FreedomService
{
    private static final Map<String, String> DEFAULT_WORLDS = Map.of("flatlands", "flatlands-template");

    /** Root-level and world-level keys {@link #generationFingerprint} pulls out; see its own doc. */
    private static final Set<String> GENERATION_ROOT_KEYS = Set.of("shape", "palette", "features");
    private static final Set<String> GENERATION_WORLD_KEYS = Set.of("environment", "generateStructures", "seed", "vanilla");

    private final ProfileLoader loader;
    private final ProfileParser parser;
    private final Map<String, GenerationProfile> profiles;

    public GenerationService(final TotalFreedomMod plugin)
    {
        super(plugin);

        this.loader = new ProfileLoader(plugin);
        this.parser = new ProfileParser();
        this.profiles = new HashMap<>();
    }

    @Override
    protected void onStart()
    {
        final Map<String, JsonObject> biomeLibrary;

        try 
        {
            biomeLibrary = this.loader.biomeLibrary();
        }
        catch (final ProfileException ex)
        {
            FLog.severe("Failed to load biome library: " + ExceptionUtils.getRootCauseMessage(ex));
            Bukkit.getPluginManager().disablePlugin(plugin); // we don't want to load TFM because no worlds can be loaded.
            return;
        }

        this.loader
            .available()
            .forEach(name -> this.loadProfile(name, biomeLibrary));

        if (this.profiles.isEmpty())
        {
            seedDefaultWorlds();
            this.loader
                .available()
                .forEach(name -> this.loadProfile(name, biomeLibrary));
        }
    }

    /**
     * Writes every one of {@link #DEFAULT_WORLDS} out from its bundled template. Only ever called
     * once, when nothing on disk parsed into a usable profile; {@link ProfileLoader#copyTemplate}
     * itself also refuses to overwrite a file that's already there, so a broken-but-present
     * flatlands.json is left alone rather than sent to the gulag.
     */
    private void seedDefaultWorlds()
    {
        DEFAULT_WORLDS.forEach((worldName, templateName) -> this.loader.copyTemplate(templateName, worldName));
    }

    @Override
    protected void onStop()
    {

    }

    public Optional<GenerationProfile> profile(final String worldName)
    {
        return Optional.ofNullable(profiles.get(worldName));
    }

    /** Empty if no profile covers the world, or if its file failed to parse. */
    public Optional<ChunkGenerator> generatorFor(final String worldName)
    {
        return profile(worldName).map(p -> new ProfileChunkGenerator(p));
    }

    /** A world's event-suppression flags, or {@link WorldSettings.Blocking#NONE} for a world with no profile. */
    public WorldSettings.Blocking blocking(final String worldName)
    {
        return profile(worldName).map(p -> p.world().blocking()).orElse(WorldSettings.Blocking.NONE);
    }

    /** Only worlds whose profiles parsed. A file that failed does not appear here. */
    public Set<String> available()
    {
        return profiles.keySet();
    }

    /**
     * The subset of a world's profile JSON that is treated as constant once generated: shape,
     * palette, features, and the handful of "world" keys the WorldCreator/ChunkGenerator themselves
     * consume. Any difference at all here between what's stored and what's on disk now means the
     * world gets archived and regenerated fresh rather than growing new chunks under different
     * settings, which seams visibly at old/new borders.
     * <p>
     * A behavioral flag (access, roExempt, weatherDisabled, blocking, ...) is deliberately left out:
     * every one of those is read live wherever it matters instead of baked into the world once, so
     * it can change in the JSON at any time with no archive and nothing to reload. This is a
     * whitelist rather than an exclusion list specifically so a new behavioral flag never needs to
     * be added here to stay out of the comparison.
     * <p>
     * Re-serialized from the parsed JSON, so it is stable against irrelevant formatting changes.
     * Only meant for that drift check; everything else should read {@link #profile}, the full parsed
     * and checked form.
     */
    public Optional<String> generationFingerprint(final String worldName)
    {
        try
        {
            return this.loader.read(worldName).map(GenerationService::extractGenerationFingerprint);
        }
        catch (final ProfileException ex)
        {
            FLog.warning("Could not re-read profile JSON for \"" + worldName + "\": " + ExceptionUtils.getRootCauseMessage(ex));
            return Optional.empty();
        }
    }

    private static String extractGenerationFingerprint(final JsonObject root)
    {
        final JsonObject fingerprint = new JsonObject();
        GENERATION_ROOT_KEYS.forEach(key -> copyIfPresent(root, fingerprint, key));

        final JsonObject worldNode = root.has("world") && root.get("world").isJsonObject() ? root.getAsJsonObject("world") : new JsonObject();
        final JsonObject worldFingerprint = new JsonObject();
        GENERATION_WORLD_KEYS.forEach(key -> copyIfPresent(worldNode, worldFingerprint, key));
        fingerprint.add("world", worldFingerprint);

        return fingerprint.toString();
    }

    private static void copyIfPresent(final JsonObject source, final JsonObject target, final String key)
    {
        if (source.has(key))
            target.add(key, source.get(key));
    }

    /**
     * Re-parses every available profile and drops any no longer on disk. A profile that fails to
     * re-parse keeps its last good copy, since {@link #loadProfile} only overwrites an entry once the
     * new one parses cleanly.
     */
    public void reload()
    {
        final Map<String, JsonObject> biomeLibrary;

        try
        {
            biomeLibrary = this.loader.biomeLibrary();
        }
        catch (final ProfileException ex)
        {
            FLog.warning("Failed to reload biome library: " + ExceptionUtils.getRootCauseMessage(ex));
            // we don't want to disable the plugin here because this executes assuming worlds have already loaded.
            return;
        }

        final Set<String> available = this.loader.available();

        this.profiles.keySet().retainAll(available);
        available.forEach(name -> this.loadProfile(name, biomeLibrary));
    }

    private void loadProfile(final String worldName, final Map<String, JsonObject> biomeLibrary)
    {
        try
        {
            final Optional<JsonObject> jsonRoot = this.loader.read(worldName);

            if (jsonRoot.isEmpty())
                return;

            this.profiles.put(worldName, parser.parse(worldName, jsonRoot.get(), biomeLibrary));
        }
        catch (final ProfileException ex)
        {
            FLog.warning(String.format("Failed to parse json object for %s: \n%s", worldName, ExceptionUtils.getRootCauseMessage(ex)));
        }
    }
}
