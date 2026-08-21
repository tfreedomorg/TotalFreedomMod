package me.totalfreedom.totalfreedommod.world.profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;

import me.totalfreedom.totalfreedommod.world.GenerationProfile;
import me.totalfreedom.totalfreedommod.world.noise.NoiseField;
import me.totalfreedom.totalfreedommod.world.noise.NoiseProfile;
import me.totalfreedom.totalfreedommod.world.noise.NoiseType;

/**
 * Turns a profile file into a checked {@link GenerationProfile}, or explains why it cannot.
 * <p>
 * A missing required key is an error; a missing optional one is {@code Optional.empty()}.
 * Nothing is quietly defaulted into something that generates the wrong terrain.
 * <p>
 * Collect every problem before giving up, so one run of the server tells an admin everything wrong
 * with the file. Stopping at the first error means fixing typos one server restart at a time.
 * <p>
 * Treated as main thread only, though honestly that's more a project convention than a real API
 * requirement; {@link Material#valueOf} and a {@link RegistryKey#BIOME} lookup are just lookups and
 * don't actually need the main thread. Keeping every Bukkit-facing lookup on one thread just means
 * nobody has to double check that assumption later as this file grows. Reading the file itself is
 * not bound the same way, so do that first and hand in the parsed JSON.
 * <p>
 * A {@link FeatureSpec}'s own {@code "type"} key is the variant discriminator, so no
 * {@link FeatureDetail} variant's fields may reuse that name.
 */
public final class ProfileParser
{
    /**
     * @param worldName    the file's name, which becomes the world's name
     * @param biomeLibrary every biome definition a {@code {"ref": "..."}} may name, from
     *                     {@link ProfileLoader#biomeLibrary()}
     * @throws ProfileException carrying every problem found, never just the first
     */
    public GenerationProfile parse(final String worldName, 
                                   final JsonObject root, 
                                   final Map<String, JsonObject> biomeLibrary) throws ProfileException
    {
        final List<ProfileError> errors = new ArrayList<>();
        final long seed = resolveSeed(worldName, root);

        final Optional<ParsedShape> shape = parseShapeSection(root, errors);
        final Optional<Palette> palette = parsePalette(root, errors, seed, biomeLibrary);
        final List<FeatureSpec> features = optionalArray(root, "features", "", errors)
            .map(array -> parseFeatures(array, "features", errors))
            .orElse(List.of());
        final Optional<WorldSettings> world = parseWorldSettings(root, errors);

        if (!errors.isEmpty())
            throw new ProfileException(worldName, errors);

        return new GenerationProfile(worldName, shape.get().bounds(), shape.get().shape(), palette.get(), features, world.get());
    }

    /** shape.bounds and shape's own mode-specific fields, parsed together since they share one JSON object. */
    private record ParsedShape(Bounds bounds, Shape shape)
    {
    }

    /**
     * A pinned seed if world.seed is a valid number (or a quoted number; Gson happily coerces either),
     * else one derived from the world's own name, so re-parsing an unseeded profile after a restart
     * still produces the same terrain. Resolved before anything else, since palette.climate needs a
     * seed to build its NoiseFields. Never records an error itself; a malformed world.seed is reported
     * properly later, by parseWorldSettings.
     */
    private static long resolveSeed(final String worldName, final JsonObject root)
    {
        final JsonElement worldNode = root.get("world");

        if (worldNode != null && worldNode.isJsonObject())
        {
            final JsonElement seedElement = worldNode.getAsJsonObject().get("seed");

            if (seedElement != null && seedElement.isJsonPrimitive() && !seedElement.getAsJsonPrimitive().isBoolean())
            {
                try
                {
                    return seedElement.getAsLong();
                }
                catch (final NumberFormatException ignored)
                {
                    // Falls through; parseWorldSettings reports the real problem against world.seed.
                }
            }
        }

        return worldName.hashCode();
    }

    private static Optional<ParsedShape> parseShapeSection(final JsonObject root, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(root, "shape", "", errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = "shape";
        final Optional<Bounds> bounds = parseBounds(node.get(), path, errors);
        final Optional<String> mode = requireString(node.get(), "mode", path, errors);

        if (bounds.isEmpty() || mode.isEmpty())
            return Optional.empty();

        final Optional<Shape> shape = switch (mode.get().toLowerCase(Locale.ROOT))
        {
            case "flat" -> parseFlatShape(node.get(), path, errors, bounds.get());
            case "heightmap" -> parseHeightmapShape(node.get(), path, errors, bounds.get());
            case "density" -> parseDensityShape(node.get(), path, errors, bounds.get());
            default ->
            {
                errors.add(new ProfileError(childPath(path, "mode"), "unknown mode \"" + mode.get() + "\""));
                yield Optional.empty();
            }
        };

        return shape.map(s -> new ParsedShape(bounds.get(), s));
    }

    private static Optional<Bounds> parseBounds(final JsonObject shapeNode, 
                                                final String parentPath, 
                                                final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(shapeNode, "bounds", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "bounds");
        final Optional<Integer> minY = requireInt(node.get(), "minY", path, errors);
        final Optional<Integer> maxY = requireInt(node.get(), "maxY", path, errors);
        final Optional<Integer> seaLevel = optionalInt(node.get(), "seaLevel", path, errors);

        if (minY.isEmpty() || maxY.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(new Bounds(minY.get(), maxY.get(), seaLevel));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<Shape> parseFlatShape(final JsonObject shapeNode, 
                                                  final String path, 
                                                  final List<ProfileError> errors,
                                                  final Bounds bounds)
    {
        final Optional<String> spec = requireString(shapeNode, "layers", path, errors);
        if (spec.isEmpty())
            return Optional.empty();

        try
        {
            final LayerStack layers = LayerStack.parse(spec.get());
            final int available = bounds.maxY() - bounds.minY();

            if (layers.totalHeight() > available)
            {
                errors.add(new ProfileError(childPath(path, "layers"), "total layer height (" + layers.totalHeight()
                    + ") exceeds the world's own bounds (" + available + " blocks, Y=" + bounds.minY() + " to Y=" + bounds.maxY() + ")"));
                return Optional.empty();
            }

            return Optional.of(new Shape.Flat(layers));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(childPath(path, "layers"), ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<Shape> parseHeightmapShape(final JsonObject shapeNode, 
                                                       final String path, 
                                                       final List<ProfileError> errors, 
                                                       final Bounds bounds)
    {
        final Optional<JsonObject> terrainNode = requireObject(shapeNode, "terrain", path, errors);
        final Optional<Shape.Terrain> terrain = terrainNode.flatMap(node -> parseShapeTerrain(node, childPath(path, "terrain"), errors));

        final boolean hasRiver = hasKey(shapeNode, "river");
        final Optional<Shape.River> river = hasRiver ? parseRiver(shapeNode, path, errors) : Optional.empty();

        final boolean hasCaves = hasKey(shapeNode, "caves");
        final Optional<Shape.Caves> caves = hasCaves ? parseCaves(shapeNode, path, errors, bounds) : Optional.empty();

        final boolean hasRegions = hasKey(shapeNode, "regions");
        final Optional<Shape.Regions<Shape.Terrain>> regions = hasRegions
            ? parseRegions(shapeNode, path, errors, (node, p) -> parseShapeTerrain(node, p, errors))
            : Optional.empty();

        if (terrain.isEmpty() || (hasRiver && river.isEmpty()) || (hasCaves && caves.isEmpty()) || (hasRegions && regions.isEmpty()))
            return Optional.empty();

        return Optional.of(new Shape.Heightmap(terrain.get(), river, caves, regions));
    }

    private static Optional<Shape> parseDensityShape(final JsonObject shapeNode, 
                                                     final String path, 
                                                     final List<ProfileError> errors, 
                                                     final Bounds bounds)
    {
        final Optional<JsonObject> terrainNode = requireObject(shapeNode, "terrain", path, errors);
        final Optional<Shape.DensityLayer> terrain = terrainNode.flatMap(node -> parseDensityLayer(node, childPath(path, "terrain"), errors));

        final boolean hasCaves = hasKey(shapeNode, "caves");
        final Optional<Shape.Caves> caves = hasCaves ? parseCaves(shapeNode, path, errors, bounds) : Optional.empty();

        final boolean hasRegions = hasKey(shapeNode, "regions");
        final Optional<Shape.Regions<Shape.DensityLayer>> regions = hasRegions
            ? parseRegions(shapeNode, path, errors, (node, p) -> parseDensityLayer(node, p, errors))
            : Optional.empty();

        if (terrain.isEmpty() || (hasCaves && caves.isEmpty()) || (hasRegions && regions.isEmpty()))
            return Optional.empty();

        return Optional.of(new Shape.Density(terrain.get().noise(), terrain.get().warp(), caves, regions));
    }

    private static Optional<Shape.Terrain> parseShapeTerrain(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<JsonObject> noiseNode = requireObject(node, "noise", path, errors);
        final Optional<NoiseProfile> noise = noiseNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "noise"), errors));
        final Optional<JsonObject> splineNode = requireObject(node, "spline", path, errors);
        final Optional<Spline> spline = splineNode.flatMap(n -> parseSpline(n, childPath(path, "spline"), errors));
        final Optional<Double> warp = requireDouble(node, "warp", path, errors);

        if (noise.isEmpty() || spline.isEmpty() || warp.isEmpty())
            return Optional.empty();

        return Optional.of(new Shape.Terrain(noise.get(), spline.get(), warp.get()));
    }

    private static Optional<Shape.DensityLayer> parseDensityLayer(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<JsonObject> noiseNode = requireObject(node, "noise", path, errors);
        final Optional<NoiseProfile> noise = noiseNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "noise"), errors));
        final Optional<Double> warp = requireDouble(node, "warp", path, errors);

        if (noise.isEmpty() || warp.isEmpty())
            return Optional.empty();

        return Optional.of(new Shape.DensityLayer(noise.get(), warp.get()));
    }

    private static Optional<Shape.River> parseRiver(final JsonObject parent, final String parentPath, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(parent, "river", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "river");
        final Optional<JsonObject> noiseNode = requireObject(node.get(), "noise", path, errors);
        final Optional<NoiseProfile> noise = noiseNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "noise"), errors));
        final Optional<Double> threshold = requireDouble(node.get(), "threshold", path, errors);
        final Optional<Integer> depth = requireInt(node.get(), "depth", path, errors);
        final Optional<BlockData> bedBlock = requireBlock(node.get(), "bedBlock", path, errors);

        if (noise.isEmpty() || threshold.isEmpty() || depth.isEmpty() || bedBlock.isEmpty())
            return Optional.empty();

        return Optional.of(new Shape.River(noise.get(), threshold.get(), depth.get(), bedBlock.get()));
    }

    private static Optional<Shape.Caves> parseCaves(final JsonObject parent, final String parentPath, final List<ProfileError> errors, final Bounds bounds)
    {
        final Optional<JsonObject> node = requireObject(parent, "caves", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "caves");
        final Optional<JsonObject> noiseNode = requireObject(node.get(), "noise", path, errors);
        final Optional<NoiseProfile> noise = noiseNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "noise"), errors));
        final Optional<Double> threshold = requireDouble(node.get(), "threshold", path, errors);
        final Optional<Integer> minY = requireInt(node.get(), "minY", path, errors);
        final Optional<Integer> maxY = requireInt(node.get(), "maxY", path, errors);
        final Optional<Integer> floodLevel = requireInt(node.get(), "floodLevel", path, errors);

        if (noise.isEmpty() || threshold.isEmpty() 
                            || minY.isEmpty() 
                            || maxY.isEmpty() 
                            || floodLevel.isEmpty())
            return Optional.empty();

        if (minY.get() < bounds.minY() || maxY.get() > bounds.maxY())
        {
            errors.add(new ProfileError(path, 
                                        String.format("minY/maxY (%d to %d) must fall within the world's own bounds (Y=%d to Y=%d)", 
                                                      minY.get(), 
                                                      maxY.get(), 
                                                      bounds.minY(), 
                                                      bounds.maxY())));
            return Optional.empty();
        }

        if (floodLevel.get() < bounds.minY() || floodLevel.get() > bounds.maxY())
        {
            errors.add(new ProfileError(childPath(path, "floodLevel"), 
                                        String.format("floodLevel (%d) must fall within the world's own bounds (Y=%d to Y=%d)", 
                                                      floodLevel.get(),
                                                      bounds.minY(), 
                                                      bounds.maxY())));
            return Optional.empty();
        }

        try
        {
            return Optional.of(new Shape.Caves(noise.get(), 
                                               threshold.get(), 
                                               minY.get(), 
                                               maxY.get(), 
                                               floodLevel.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    /** Shared by Heightmap and Density; T is whichever terrain shape that mode's regions carry. */
    private static <T> Optional<Shape.Regions<T>> parseRegions(final JsonObject parent, final String parentPath,
                                                                final List<ProfileError> errors,
                                                                final BiFunction<JsonObject, String, Optional<T>> terrainParser)
    {
        final Optional<JsonObject> node = requireObject(parent, "regions", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "regions");
        final Optional<JsonObject> selectorNode = requireObject(node.get(), "selector", path, errors);
        final Optional<NoiseProfile> selector = selectorNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "selector"), errors));
        final Optional<Double> blendWidth = requireDouble(node.get(), "blendWidth", path, errors);
        final Optional<JsonArray> regionArray = requireArray(node.get(), "regions", path, errors);

        if (selector.isEmpty() || blendWidth.isEmpty() || regionArray.isEmpty())
            return Optional.empty();

        final List<Shape.Region<T>> regions = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        final boolean[] valid = { true };

        IntStream.range(0, regionArray.get().size()).forEach(i ->
        {
            final String regionPath = childPath(path, "regions") + "[" + i + "]";
            final JsonElement element = regionArray.get().get(i);

            if (!element.isJsonObject())
            {
                errors.add(new ProfileError(regionPath, "expected an object, got \"" + element + "\""));
                valid[0] = false;
                return;
            }

            final JsonObject regionNode = element.getAsJsonObject();
            final Optional<String> name = requireString(regionNode, "name", regionPath, errors);
            final Optional<Double> min = requireDouble(regionNode, "min", regionPath, errors);
            final Optional<Double> max = requireDouble(regionNode, "max", regionPath, errors);
            final Optional<JsonObject> terrainNode = requireObject(regionNode, "terrain", regionPath, errors);
            final Optional<T> terrain = terrainNode.flatMap(n -> terrainParser.apply(n, childPath(regionPath, "terrain")));

            if (name.isEmpty() || min.isEmpty() || max.isEmpty() || terrain.isEmpty())
            {
                valid[0] = false;
                return;
            }

            if (!seenNames.add(name.get()))
            {
                errors.add(new ProfileError(childPath(regionPath, "name"), "duplicate region name \"" + name.get() + "\""));
                valid[0] = false;
                return;
            }

            try
            {
                regions.add(new Shape.Region<>(name.get(), min.get(), max.get(), terrain.get()));
            }
            catch (final IllegalArgumentException ex)
            {
                errors.add(new ProfileError(regionPath, ex.getMessage()));
                valid[0] = false;
            }
        });

        if (!valid[0])
            return Optional.empty();

        if (hasOverlappingCores(regions, path, errors))
            return Optional.empty();

        try
        {
            return Optional.of(new Shape.Regions<>(selector.get(), blendWidth.get(), regions));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Regions are meant to overlap only within blendWidth of a shared border, which is what turns
     * that border into a gradient; see {@link Shape.Region}. An outright overlap of two regions' own
     * min/max would instead average them at full strength across their shared territory, which reads
     * as a configuration mistake rather than an intended blend, so every overlapping pair is reported.
     */
    private static <T> boolean hasOverlappingCores(final List<Shape.Region<T>> regions, final String path, final List<ProfileError> errors)
    {
        final boolean[] overlapping = { false };

        IntStream.range(0, regions.size()).forEach(i ->
            IntStream.range(i + 1, regions.size()).forEach(j ->
            {
                final Shape.Region<T> a = regions.get(i);
                final Shape.Region<T> b = regions.get(j);

                if (a.min() <= b.max() && b.min() <= a.max())
                {
                    errors.add(new ProfileError(path, 
                                                String.format("regions \"%s\" and \"%s\" overlap (%d to %d vs %d to %d); narrow their ranges so they meet at most within blendWidth",
                                                              a.name(), 
                                                              b.name(),
                                                              a.min(),
                                                              a.max(),
                                                              b.min(),
                                                              b.max())));
                    overlapping[0] = true;
                }
            }));

        return overlapping[0];
    }

    private static Optional<NoiseProfile> parseNoiseProfile(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<NoiseType> type = requireEnum(node, "type", path, errors, "noise type", NoiseType::valueOf);
        final Optional<Integer> octaves = requireInt(node, "octaves", path, errors);
        final Optional<Double> frequency = requireDouble(node, "frequency", path, errors);
        final Optional<Double> persistence = requireDouble(node, "persistence", path, errors);
        final Optional<Double> lacunarity = requireDouble(node, "lacunarity", path, errors);
        final Optional<Boolean> ridged = requireBoolean(node, "ridged", path, errors);

        if (type.isEmpty() || octaves.isEmpty() || frequency.isEmpty() || persistence.isEmpty() || lacunarity.isEmpty() || ridged.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(new NoiseProfile(type.get(), octaves.get(), frequency.get(), persistence.get(), lacunarity.get(), ridged.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<Spline> parseSpline(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<JsonArray> pointsArray = requireArray(node, "points", path, errors);
        if (pointsArray.isEmpty())
            return Optional.empty();

        final List<double[]> points = new ArrayList<>();
        final boolean[] valid = { true };
        final String pointsPath = childPath(path, "points");

        IntStream.range(0, pointsArray.get().size()).forEach(i ->
        {
            final JsonElement element = pointsArray.get().get(i);

            try
            {
                final JsonArray pair = element.getAsJsonArray();

                if (pair.size() != 2)
                    throw new IllegalStateException("expected a [noise, height] pair");

                points.add(new double[] { pair.get(0).getAsDouble(), pair.get(1).getAsDouble() });
            }
            catch (final RuntimeException ex)
            {
                errors.add(new ProfileError(pointsPath + "[" + i + "]", "expected a [noise, height] pair, got \"" + element + "\""));
                valid[0] = false;
            }
        });

        if (!valid[0])
            return Optional.empty();

        try
        {
            return Optional.of(Spline.of(points));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(pointsPath, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<Palette> parsePalette(final JsonObject root, final List<ProfileError> errors,
                                                  final long seed, final Map<String, JsonObject> biomeLibrary)
    {
        final Optional<JsonObject> node = requireObject(root, "palette", "", errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = "palette";
        final Optional<Materials> materials = parseMaterials(node.get(), path, errors);
        final Optional<JsonArray> surfaceArray = requireArray(node.get(), "surface", path, errors);
        final List<SurfaceRule> surface = surfaceArray.map(array -> parseSurfaceRules(array, childPath(path, "surface"), errors)).orElse(List.of());
        final Optional<Palette.Climate> climate = parseClimate(node.get(), path, errors, seed);
        final Optional<Biome> fallback = requireBiome(node.get(), "fallback", path, errors);

        final List<Palette.BiomeBand> biomes = optionalArray(node.get(), "biomes", path, errors)
            .map(array -> parseBiomeBands(array, childPath(path, "biomes"), errors, biomeLibrary))
            .orElse(List.of());

        if (materials.isEmpty() || surfaceArray.isEmpty() || climate.isEmpty() || fallback.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(new Palette(materials.get(), surface, climate.get(), fallback.get(), biomes));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<Materials> parseMaterials(final JsonObject paletteNode, final String parentPath, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(paletteNode, "materials", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "materials");
        final Optional<BlockData> defaultBlock = requireBlock(node.get(), "defaultBlock", path, errors);
        final Optional<BlockData> fluidBlock = requireBlock(node.get(), "fluidBlock", path, errors);
        final Optional<BlockData> bedrockBlock = requireBlock(node.get(), "bedrockBlock", path, errors);
        final Optional<BedrockMode> bedrock = requireEnum(node.get(), "bedrock", path, errors, "bedrock mode", BedrockMode::valueOf);

        if (defaultBlock.isEmpty() || fluidBlock.isEmpty() || bedrockBlock.isEmpty() || bedrock.isEmpty())
            return Optional.empty();

        return Optional.of(new Materials(defaultBlock.get(), fluidBlock.get(), bedrockBlock.get(), bedrock.get()));
    }

    private static Optional<Palette.Climate> parseClimate(final JsonObject paletteNode, final String parentPath,
                                                           final List<ProfileError> errors, final long seed)
    {
        final Optional<JsonObject> node = requireObject(paletteNode, "climate", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "climate");
        final Optional<JsonObject> temperatureNode = requireObject(node.get(), "temperature", path, errors);
        final Optional<NoiseProfile> temperature = temperatureNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "temperature"), errors));
        final Optional<JsonObject> humidityNode = requireObject(node.get(), "humidity", path, errors);
        final Optional<NoiseProfile> humidity = humidityNode.flatMap(n -> parseNoiseProfile(n, childPath(path, "humidity"), errors));
        final Optional<Double> scale = requireDouble(node.get(), "scale", path, errors);

        if (temperature.isEmpty() || humidity.isEmpty() || scale.isEmpty())
            return Optional.empty();

        try
        {
            final NoiseField temperatureField = NoiseField.of(temperature.get(), seed, "climate-temperature");
            final NoiseField humidityField = NoiseField.of(humidity.get(), seed, "climate-humidity");

            return Optional.of(new Palette.Climate(temperatureField, humidityField, scale.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static List<Palette.BiomeBand> parseBiomeBands(final JsonArray array, final String path, final List<ProfileError> errors,
                                                            final Map<String, JsonObject> biomeLibrary)
    {
        final List<Palette.BiomeBand> bands = new ArrayList<>();

        IntStream.range(0, array.size()).forEach(i ->
            parseBiomeBand(array.get(i), path + "[" + i + "]", errors, biomeLibrary).ifPresent(bands::add));

        return bands;
    }

    private static Optional<Palette.BiomeBand> parseBiomeBand(final JsonElement element, final String path, final List<ProfileError> errors,
                                                               final Map<String, JsonObject> biomeLibrary)
    {
        if (!element.isJsonObject())
        {
            errors.add(new ProfileError(path, "expected an object, got \"" + element + "\""));
            return Optional.empty();
        }

        final JsonObject node = element.getAsJsonObject();
        final Optional<BiomeTarget> target = parseBiomeTarget(node, path, errors, biomeLibrary);
        final Optional<Double> minTemperature = requireDouble(node, "minTemperature", path, errors);
        final Optional<Double> maxTemperature = requireDouble(node, "maxTemperature", path, errors);
        final Optional<Double> minHumidity = requireDouble(node, "minHumidity", path, errors);
        final Optional<Double> maxHumidity = requireDouble(node, "maxHumidity", path, errors);

        if (target.isEmpty() || minTemperature.isEmpty() || maxTemperature.isEmpty() || minHumidity.isEmpty() || maxHumidity.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(new Palette.BiomeBand(
                target.get(), minTemperature.get(), maxTemperature.get(), minHumidity.get(), maxHumidity.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    /** "biome": a string names a vanilla biome; an object is a Logical one, inline or by {"ref": "..."}. */
    private static Optional<BiomeTarget> parseBiomeTarget(final JsonObject bandNode, final String parentPath,
                                                           final List<ProfileError> errors, final Map<String, JsonObject> biomeLibrary)
    {
        final JsonElement element = bandNode.get("biome");
        final String path = childPath(parentPath, "biome");

        if (element == null || element.isJsonNull())
        {
            errors.add(new ProfileError(path, "missing required key"));
            return Optional.empty();
        }

        if (element.isJsonPrimitive())
            return parseBiome(element.getAsString(), path, errors).map(BiomeTarget.Vanilla::new);

        if (!element.isJsonObject())
        {
            errors.add(new ProfileError(path, "expected a string or an object, got \"" + element + "\""));
            return Optional.empty();
        }

        final JsonObject biomeNode = element.getAsJsonObject();
        final boolean hasRef = hasKey(biomeNode, "ref");
        final boolean hasDisplay = hasKey(biomeNode, "display");

        if (hasRef == hasDisplay)
        {
            errors.add(new ProfileError(path, hasRef
                ? "must not specify both \"ref\" and \"display\""
                : "must specify exactly one of \"ref\" or \"display\""));
            return Optional.empty();
        }

        if (!hasRef)
            return parseBiomeDefinition(biomeNode, path, errors, biomeLibrary).map(BiomeTarget.Logical::new);

        final Optional<String> ref = requireString(biomeNode, "ref", path, errors);
        if (ref.isEmpty())
            return Optional.empty();

        final JsonObject definitionNode = biomeLibrary.get(ref.get());
        if (definitionNode == null)
        {
            errors.add(new ProfileError(childPath(path, "ref"),
                "unknown biome \"" + ref.get() + "\", not found in the bundled or world biome library"));
            return Optional.empty();
        }

        return parseBiomeDefinition(definitionNode, path + ".ref(" + ref.get() + ")", errors, biomeLibrary).map(BiomeTarget.Logical::new);
    }

    private static Optional<BiomeDefinition> parseBiomeDefinition(final JsonObject node, final String path, final List<ProfileError> errors,
                                                                   final Map<String, JsonObject> biomeLibrary)
    {
        final Optional<Biome> display = requireBiome(node, "display", path, errors);
        if (display.isEmpty())
            return Optional.empty();

        final Optional<List<SurfaceRule>> surface = hasKey(node, "surface")
            ? requireArray(node, "surface", path, errors).map(array -> parseSurfaceRules(array, childPath(path, "surface"), errors))
            : Optional.empty();

        final Optional<List<FeatureSpec>> features = hasKey(node, "features")
            ? requireArray(node, "features", path, errors).map(array -> parseFeatures(array, childPath(path, "features"), errors))
            : Optional.empty();

        return Optional.of(new BiomeDefinition(display.get(), surface, features));
    }

    private static List<SurfaceRule> parseSurfaceRules(final JsonArray array, final String path, final List<ProfileError> errors)
    {
        final List<SurfaceRule> rules = new ArrayList<>();

        IntStream.range(0, array.size()).forEach(i ->
            parseSurfaceRule(array.get(i), path + "[" + i + "]", errors).ifPresent(rules::add));

        return rules;
    }

    private static Optional<SurfaceRule> parseSurfaceRule(final JsonElement element, final String path, final List<ProfileError> errors)
    {
        if (!element.isJsonObject())
        {
            errors.add(new ProfileError(path, "expected an object, got \"" + element + "\""));
            return Optional.empty();
        }

        final JsonObject node = element.getAsJsonObject();
        final BiomeFilter biomes = parseSurfaceBiomeFilter(node, path, errors);
        final Optional<Depth> depth = parseDepth(node, path, errors);
        final Optional<BlockData> block = requireBlock(node, "block", path, errors);

        if (depth.isEmpty() || block.isEmpty())
            return Optional.empty();

        return Optional.of(new SurfaceRule(biomes, depth.get(), block.get()));
    }

    private static Optional<Depth> parseDepth(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<Integer> from = requireInt(node, "depthFrom", path, errors);
        final Optional<Integer> to = optionalInt(node, "depthTo", path, errors);

        if (from.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(to.isPresent() ? Depth.range(from.get(), to.get()) : Depth.rest(from.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    /** SurfaceRule's own shape: a single optional "biome" string. Absent means Any(). */
    private static BiomeFilter parseSurfaceBiomeFilter(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<String> name = optionalString(node, "biome", path, errors);
        if (name.isEmpty())
            return BiomeFilter.any();

        return parseBiome(name.get(), childPath(path, "biome"), errors).<BiomeFilter>map(biome -> BiomeFilter.of(Set.of(biome)))
                                                                            .orElseGet(BiomeFilter::any);
    }

    /** A feature or biome band's own shape: an optional "biomes" array of strings. Absent means Any(). */
    private static BiomeFilter parseFeatureBiomeFilter(final JsonObject node, final String path, final List<ProfileError> errors)
    {
        final Optional<JsonArray> array = optionalArray(node, "biomes", path, errors);
        if (array.isEmpty())
            return BiomeFilter.any();

        final Set<Biome> biomes = new LinkedHashSet<>();
        final String biomesPath = childPath(path, "biomes");

        IntStream.range(0, array.get().size()).forEach(i ->
        {
            final JsonElement element = array.get().get(i);

            try
            {
                final Biome biome = RegistryAccess.registryAccess()
                                                   .getRegistry(RegistryKey.BIOME)
                                                   .get(Key.key(element.getAsString().toLowerCase(Locale.ROOT)));

                if (biome == null)
                    throw new IllegalArgumentException();

                biomes.add(biome);
            }
            catch (final RuntimeException ex)
            {
                errors.add(new ProfileError(biomesPath + "[" + i + "]", "unknown biome \"" + element + "\""));
            }
        });

        if (biomes.isEmpty())
        {
            errors.add(new ProfileError(biomesPath, "must not be empty"));
            return BiomeFilter.any();
        }

        return BiomeFilter.of(biomes);
    }

    private static List<FeatureSpec> parseFeatures(final JsonArray array, final String path, final List<ProfileError> errors)
    {
        final List<FeatureSpec> specs = new ArrayList<>();

        IntStream.range(0, array.size()).forEach(i ->
            parseFeatureSpec(array.get(i), path + "[" + i + "]", errors).ifPresent(specs::add));

        return specs;
    }

    private static Optional<FeatureSpec> parseFeatureSpec(final JsonElement element, final String path, final List<ProfileError> errors)
    {
        if (!element.isJsonObject())
        {
            errors.add(new ProfileError(path, "expected an object, got \"" + element + "\""));
            return Optional.empty();
        }

        final JsonObject node = element.getAsJsonObject();
        final Optional<String> type = requireString(node, "type", path, errors);
        final Optional<Integer> rarity = requireInt(node, "rarity", path, errors);
        final Optional<Integer> minY = requireInt(node, "minY", path, errors);
        final Optional<Integer> maxY = requireInt(node, "maxY", path, errors);
        final BiomeFilter biomes = parseFeatureBiomeFilter(node, path, errors);

        if (type.isEmpty() || rarity.isEmpty() || minY.isEmpty() || maxY.isEmpty())
            return Optional.empty();

        final Optional<FeatureDetail> detail = parseFeatureDetail(node, path, type.get().toLowerCase(Locale.ROOT), errors);
        if (detail.isEmpty())
            return Optional.empty();

        try
        {
            return Optional.of(new FeatureSpec(rarity.get(), minY.get(), maxY.get(), biomes, detail.get()));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<FeatureDetail> parseFeatureDetail(final JsonObject node, final String path, final String type,
                                                               final List<ProfileError> errors)
    {
        return switch (type)
        {
            case "ore" ->
            {
                final Optional<BlockData> block = requireBlock(node, "block", path, errors);
                final Optional<Integer> size = requireInt(node, "size", path, errors);
                yield (block.isEmpty() || size.isEmpty())
                    ? Optional.empty()
                    : buildDetail(path, errors, () -> new FeatureDetail.Ore(block.get(), size.get()));
            }
            case "patch" ->
            {
                final Optional<BlockData> block = requireBlock(node, "block", path, errors);
                final Optional<Integer> spread = requireInt(node, "size", path, errors);
                yield (block.isEmpty() || spread.isEmpty())
                    ? Optional.empty()
                    : buildDetail(path, errors, () -> new FeatureDetail.Patch(block.get(), spread.get()));
            }
            case "lake" ->
            {
                final Optional<BlockData> fluid = requireBlock(node, "block", path, errors);
                final Optional<Integer> radius = requireInt(node, "size", path, errors);
                yield (fluid.isEmpty() || radius.isEmpty())
                    ? Optional.empty()
                    : buildDetail(path, errors, () -> new FeatureDetail.Lake(fluid.get(), radius.get()));
            }
            case "boulder" ->
            {
                final Optional<BlockData> block = requireBlock(node, "block", path, errors);
                final Optional<Integer> radius = requireInt(node, "size", path, errors);
                yield (block.isEmpty() || radius.isEmpty())
                    ? Optional.empty()
                    : buildDetail(path, errors, () -> new FeatureDetail.Boulder(block.get(), radius.get()));
            }
            case "tree" ->
            {
                if (hasKey(node, "treeType"))
                {
                    final Optional<TreeType> treeType = requireEnum(node, "treeType", path, errors, "tree type", TreeType::valueOf);
                    yield treeType.map(FeatureDetail.Tree::new);
                }

                yield Optional.of(new FeatureDetail.NaturalTree());
            }
            default ->
            {
                errors.add(new ProfileError(childPath(path, "type"), "unknown feature type \"" + type + "\""));
                yield Optional.empty();
            }
        };
    }

    /** Runs a feature detail's constructor, turning the IllegalArgumentException its validation may throw into a ProfileError. */
    private static Optional<FeatureDetail> buildDetail(final String path, final List<ProfileError> errors,
                                                        final Supplier<FeatureDetail> constructor)
    {
        try
        {
            return Optional.of(constructor.get());
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, ex.getMessage()));
            return Optional.empty();
        }
    }

    private static Optional<WorldSettings> parseWorldSettings(final JsonObject root, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(root, "world", "", errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = "world";
        final Optional<World.Environment> environment =
            requireEnum(node.get(), "environment", path, errors, "environment", World.Environment::valueOf);
        final Optional<Boolean> generateStructures = requireBoolean(node.get(), "generateStructures", path, errors);
        final Optional<Boolean> keepSpawnLoaded = requireBoolean(node.get(), "keepSpawnLoaded", path, errors);
        final Optional<Long> seed = optionalLong(node.get(), "seed", path, errors);
        final boolean hasAccess = hasKey(node.get(), "access");
        final Optional<WorldSettings.Access> access = hasAccess ? parseAccess(node.get(), path, errors) : Optional.empty();
        final boolean roExempt = optionalBoolean(node.get(), "roExempt", path, errors).orElse(false);
        final boolean weatherDisabled = optionalBoolean(node.get(), "weatherDisabled", path, errors).orElse(false);
        final boolean hasBlocking = hasKey(node.get(), "blocking");
        final Optional<WorldSettings.Blocking> blocking = hasBlocking ? parseBlocking(node.get(), path, errors) : Optional.of(WorldSettings.Blocking.NONE);
        final Optional<WorldSettings.VanillaFlags> vanilla = parseVanillaFlags(node.get(), path, errors);

        if (environment.isEmpty() || generateStructures.isEmpty() || keepSpawnLoaded.isEmpty()
                                   || (hasAccess && access.isEmpty()) || (hasBlocking && blocking.isEmpty()) || vanilla.isEmpty())
            return Optional.empty();

        return Optional.of(new WorldSettings(environment.get(), generateStructures.get(), keepSpawnLoaded.get(), seed, access,
                                             roExempt, weatherDisabled, blocking.get(), vanilla.get()));
    }

    private static Optional<WorldSettings.Access> parseAccess(final JsonObject worldNode, final String parentPath, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(worldNode, "access", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "access");
        final Optional<String> permission = requireString(node.get(), "permission", path, errors);

        return permission.map(WorldSettings.Access::new);
    }

    private static Optional<WorldSettings.Blocking> parseBlocking(final JsonObject worldNode, final String parentPath, final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(worldNode, "blocking", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "blocking");
        final boolean spawners = optionalBoolean(node.get(), "spawners", path, errors).orElse(false);
        final boolean spawnerPlace = optionalBoolean(node.get(), "spawnerPlace", path, errors).orElse(false);
        final boolean portalCreate = optionalBoolean(node.get(), "portalCreate", path, errors).orElse(false);
        final boolean pistons = optionalBoolean(node.get(), "pistons", path, errors).orElse(false);
        final Optional<Integer> entitySpamMax = optionalInt(node.get(), "entitySpamMax", path, errors);

        return Optional.of(new WorldSettings.Blocking(spawners, spawnerPlace, portalCreate, pistons, entitySpamMax));
    }

    private static Optional<WorldSettings.VanillaFlags> parseVanillaFlags(final JsonObject worldNode, final String parentPath,
                                                                          final List<ProfileError> errors)
    {
        final Optional<JsonObject> node = requireObject(worldNode, "vanilla", parentPath, errors);
        if (node.isEmpty())
            return Optional.empty();

        final String path = childPath(parentPath, "vanilla");
        final Optional<Boolean> surface = requireBoolean(node.get(), "surface", path, errors);
        final Optional<Boolean> caves = requireBoolean(node.get(), "caves", path, errors);
        final Optional<Boolean> decorations = requireBoolean(node.get(), "decorations", path, errors);
        final Optional<Boolean> mobs = requireBoolean(node.get(), "mobs", path, errors);
        final Optional<Boolean> structures = requireBoolean(node.get(), "structures", path, errors);

        if (surface.isEmpty() || caves.isEmpty() || decorations.isEmpty() || mobs.isEmpty() || structures.isEmpty())
            return Optional.empty();

        return Optional.of(new WorldSettings.VanillaFlags(surface.get(), caves.get(), decorations.get(), mobs.get(), structures.get()));
    }

    // Every requireX/optionalX below records a ProfileError itself before returning empty, so a
    // caller can always tell "already reported" apart from "fine, wasn't there" without adding its
    // own error.

    private static String childPath(final String parentPath, final String key)
    {
        return parentPath.isEmpty() ? key : parentPath + "." + key;
    }

    private static boolean hasKey(final JsonObject obj, final String key)
    {
        final JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull();
    }

    private static Optional<JsonElement> presentField(final JsonObject obj, final String key, final String parentPath,
                                                       final boolean required, final List<ProfileError> errors)
    {
        final JsonElement element = obj.get(key);

        if (element == null || element.isJsonNull())
        {
            if (required)
                errors.add(new ProfileError(childPath(parentPath, key), "missing required key"));

            return Optional.empty();
        }

        return Optional.of(element);
    }

    private static Optional<String> asString(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsString());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected a string, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<Integer> asInt(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsInt());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected a number, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<Long> asLong(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsLong());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected a number, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<Double> asDouble(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsDouble());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected a number, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<Boolean> asBoolean(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsBoolean());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected true or false, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<JsonObject> asObject(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsJsonObject());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected an object, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<JsonArray> asArray(final JsonElement element, final String key, final String parentPath, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(element.getAsJsonArray());
        }
        catch (final RuntimeException ex)
        {
            errors.add(new ProfileError(childPath(parentPath, key), "expected an array, got \"" + element + "\""));
            return Optional.empty();
        }
    }

    private static Optional<String> requireString(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asString(e, key, path, errors));
    }

    private static Optional<String> optionalString(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, false, errors).flatMap(e -> asString(e, key, path, errors));
    }

    private static Optional<Integer> requireInt(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asInt(e, key, path, errors));
    }

    private static Optional<Integer> optionalInt(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, false, errors).flatMap(e -> asInt(e, key, path, errors));
    }

    private static Optional<Long> optionalLong(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, false, errors).flatMap(e -> asLong(e, key, path, errors));
    }

    private static Optional<Boolean> optionalBoolean(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, false, errors).flatMap(e -> asBoolean(e, key, path, errors));
    }

    private static Optional<Double> requireDouble(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asDouble(e, key, path, errors));
    }

    private static Optional<Boolean> requireBoolean(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asBoolean(e, key, path, errors));
    }

    private static Optional<JsonObject> requireObject(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asObject(e, key, path, errors));
    }

    private static Optional<JsonArray> requireArray(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, true, errors).flatMap(e -> asArray(e, key, path, errors));
    }

    private static Optional<JsonArray> optionalArray(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return presentField(obj, key, path, false, errors).flatMap(e -> asArray(e, key, path, errors));
    }

    private static <T extends Enum<T>> Optional<T> requireEnum(final JsonObject obj, final String key, final String path,
                                                                final List<ProfileError> errors, final String typeName,
                                                                final Function<String, T> valueOf)
    {
        return requireString(obj, key, path, errors).flatMap(name -> parseEnum(name, childPath(path, key), errors, typeName, valueOf));
    }

    private static <T> Optional<T> parseEnum(final String name, final String path, final List<ProfileError> errors,
                                             final String typeName, final Function<String, T> valueOf)
    {
        try
        {
            return Optional.of(valueOf.apply(name.toUpperCase(Locale.ROOT)));
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, "unknown " + typeName + " \"" + name + "\""));
            return Optional.empty();
        }
    }

    private static Optional<BlockData> requireBlock(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return requireString(obj, key, path, errors).flatMap(name -> parseBlock(name, childPath(path, key), errors));
    }

    private static Optional<BlockData> parseBlock(final String name, final String path, final List<ProfileError> errors)
    {
        try
        {
            return Optional.of(Material.valueOf(name.toUpperCase(Locale.ROOT)).createBlockData());
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, "unknown block \"" + name + "\""));
            return Optional.empty();
        }
    }

    private static Optional<Biome> requireBiome(final JsonObject obj, final String key, final String path, final List<ProfileError> errors)
    {
        return requireString(obj, key, path, errors).flatMap(name -> parseBiome(name, childPath(path, key), errors));
    }

    private static Optional<Biome> parseBiome(final String name, final String path, final List<ProfileError> errors)
    {
        try
        {
            final Biome biome = RegistryAccess.registryAccess()
                                               .getRegistry(RegistryKey.BIOME)
                                               .get(Key.key(name.toLowerCase(Locale.ROOT)));

            if (biome == null)
                throw new IllegalArgumentException();

            return Optional.of(biome);
        }
        catch (final IllegalArgumentException ex)
        {
            errors.add(new ProfileError(path, "unknown biome \"" + name + "\""));
            return Optional.empty();
        }
    }
}
