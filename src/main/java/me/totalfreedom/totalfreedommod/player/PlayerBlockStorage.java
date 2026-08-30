package me.totalfreedom.totalfreedommod.player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import me.totalfreedom.totalfreedommod.util.FLog;

final class PlayerBlockStorage
{
    private static final String BLOCKS_PATH = "blocks";
    private static final int CONFIG_VERSION = 1;

    private final Path configPath;

    PlayerBlockStorage(final Path configPath)
    {
        this.configPath = configPath;
    }

    Optional<Map<UUID, Set<UUID>>> read()
    {
        try
        {
            return readSafely();
        }
        catch (RuntimeException ex)
        {
            FLog.error(String.format(
                    "Could not read %s safely; preserving the existing file: %s",
                    filename(),
                    ex.getMessage()));
            return Optional.empty();
        }
    }

    boolean write(final Map<UUID, Set<UUID>> blockedPlayers)
    {
        final Path temporaryPath = configPath.resolveSibling(String.format("%s.tmp", filename()));
        try
        {
            return writeSafely(blockedPlayers, temporaryPath);
        }
        catch (RuntimeException ex)
        {
            FLog.error(String.format(
                    "Could not save %s safely: %s",
                    filename(),
                    ex.getMessage()));
            removeTemporaryFile(temporaryPath);
            return false;
        }
    }

    private Optional<Map<UUID, Set<UUID>>> readSafely()
    {
        if (!Files.exists(configPath))
            return Optional.of(Map.of());

        final YamlConfiguration config = new YamlConfiguration();
        try
        {
            config.load(configPath.toFile());
        }
        catch (IOException | InvalidConfigurationException ex)
        {
            FLog.error(String.format(
                    "Could not load %s; preserving the existing file: %s",
                    filename(),
                    ex.getMessage()));
            return Optional.empty();
        }

        if (config.getInt("version", -1) != CONFIG_VERSION)
        {
            FLog.error(String.format("Unsupported or missing version in %s", filename()));
            return Optional.empty();
        }

        final Optional<ConfigurationSection> blocksSection = Optional.ofNullable(
                config.getConfigurationSection(BLOCKS_PATH));
        if (blocksSection.isEmpty())
        {
            FLog.error(String.format(
                    "Invalid or missing '%s' section in %s",
                    BLOCKS_PATH,
                    filename()));
            return Optional.empty();
        }

        final AtomicBoolean semanticErrors = new AtomicBoolean();
        final Map<UUID, Set<UUID>> blockedPlayers = new HashMap<>();
        final ConfigurationSection resolvedBlocksSection = blocksSection.orElseThrow();
        resolvedBlocksSection.getKeys(false)
                .stream()
                .sorted()
                .forEach(blockerValue -> loadBlocker(
                        resolvedBlocksSection,
                        blockerValue,
                        blockedPlayers,
                        semanticErrors));
        if (semanticErrors.get())
        {
            FLog.error(String.format(
                    "Invalid entries were found in %s; preserving the existing file",
                    filename()));
            return Optional.empty();
        }

        final Map<UUID, Set<UUID>> immutableGraph = new HashMap<>();
        blockedPlayers.forEach((blocker, targets) -> immutableGraph.put(
                blocker,
                Set.copyOf(targets)));
        return Optional.of(Map.copyOf(immutableGraph));
    }

    private boolean writeSafely(
            final Map<UUID, Set<UUID>> blockedPlayers,
            final Path temporaryPath)
    {
        if (read().isEmpty())
        {
            FLog.error(String.format(
                    "Refusing to overwrite unreadable player-block data in %s",
                    filename()));
            return false;
        }

        final YamlConfiguration config = new YamlConfiguration();
        config.set("version", CONFIG_VERSION);
        final ConfigurationSection blocksSection = config.createSection(BLOCKS_PATH);
        blockedPlayers.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> blocksSection.set(
                        entry.getKey().toString(),
                        entry.getValue()
                                .stream()
                                .sorted()
                                .map(UUID::toString)
                                .toList()));

        try
        {
            final Optional<Path> parent = Optional.ofNullable(configPath.getParent());
            if (parent.isPresent())
                Files.createDirectories(parent.orElseThrow());
            Files.writeString(temporaryPath, config.saveToString(), StandardCharsets.UTF_8);
            replaceConfig(temporaryPath);
            return true;
        }
        catch (IOException ex)
        {
            FLog.error(String.format("Could not save %s: %s", filename(), ex.getMessage()));
            removeTemporaryFile(temporaryPath);
            return false;
        }
    }

    private void loadBlocker(
            final ConfigurationSection blocksSection,
            final String blockerValue,
            final Map<UUID, Set<UUID>> blockedPlayers,
            final AtomicBoolean semanticErrors)
    {
        final Optional<UUID> blocker = parseUuid(blockerValue, "blocker", semanticErrors);
        if (blocker.isEmpty())
            return;

        final Object targetsValue = blocksSection.get(blockerValue);
        if (!(targetsValue instanceof final List<?> targets))
        {
            semanticErrors.set(true);
            FLog.warn(String.format(
                    "Ignoring non-list block entry for %s in %s",
                    blockerValue,
                    filename()));
            return;
        }

        final UUID blockerId = blocker.orElseThrow();
        final Set<UUID> loadedTargets = new TreeSet<>();
        targets.forEach(target -> loadTarget(
                blockerId,
                target,
                loadedTargets,
                semanticErrors));
        if (!loadedTargets.isEmpty())
            blockedPlayers.put(blockerId, Set.copyOf(loadedTargets));
    }

    private void loadTarget(
            final UUID blocker,
            final Object targetValue,
            final Set<UUID> loadedTargets,
            final AtomicBoolean semanticErrors)
    {
        if (!(targetValue instanceof final String targetString))
        {
            semanticErrors.set(true);
            FLog.warn(String.format(
                    "Ignoring non-string blocked player for %s in %s",
                    blocker,
                    filename()));
            return;
        }

        final Optional<UUID> target = parseUuid(targetString, "blocked player", semanticErrors);
        if (target.isEmpty())
            return;

        final UUID targetId = target.orElseThrow();
        if (blocker.equals(targetId))
        {
            semanticErrors.set(true);
            FLog.warn(String.format(
                    "Ignoring self-block entry for %s in %s",
                    blocker,
                    filename()));
            return;
        }
        loadedTargets.add(targetId);
    }

    private Optional<UUID> parseUuid(
            final String value,
            final String description,
            final AtomicBoolean semanticErrors)
    {
        try
        {
            return Optional.of(UUID.fromString(value));
        }
        catch (IllegalArgumentException ex)
        {
            semanticErrors.set(true);
            FLog.warn(String.format(
                    "Ignoring invalid %s UUID '%s' in %s",
                    description,
                    value,
                    filename()));
            return Optional.empty();
        }
    }

    private String filename()
    {
        return Optional.ofNullable(configPath.getFileName())
                .map(Path::toString)
                .orElse("player-blocks.yml");
    }

    private void replaceConfig(final Path temporaryPath) throws IOException
    {
        try
        {
            Files.move(
                    temporaryPath,
                    configPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ex)
        {
            Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void removeTemporaryFile(final Path temporaryPath)
    {
        try
        {
            Files.deleteIfExists(temporaryPath);
        }
        catch (IOException ex)
        {
            FLog.warn(String.format(
                    "Could not remove temporary %s file: %s",
                    filename(),
                    ex.getMessage()));
        }
    }
}
