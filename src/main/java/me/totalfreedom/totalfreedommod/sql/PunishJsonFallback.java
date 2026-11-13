package me.totalfreedom.totalfreedommod.sql;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import me.totalfreedom.api.sql.adapter.PunishmentHistoryRepository;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

public final class PunishJsonFallback implements PunishmentHistoryRepository
{
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<PunishmentHistoryEntry>>() {}.getType();
    private static final Comparator<PunishmentHistoryEntry> NEWEST_FIRST =
            Comparator.comparingLong(PunishmentHistoryEntry::appliedAt)
                    .thenComparingLong(PunishmentHistoryEntry::id)
                    .reversed();

    private final File file;

    public PunishJsonFallback(File file)
    {
        this.file = file;
    }

    @Override
    public synchronized void recordPunishment(PunishmentHistoryEntry entry) throws SQLException
    {
        final List<PunishmentHistoryEntry> entries = load();
        if (entries.stream().anyMatch(existing -> isSamePunishment(existing, entry)))
        {
            return;
        }

        final long nextId = entries.stream()
                .mapToLong(PunishmentHistoryEntry::id)
                .max()
                .orElse(0L) + 1L;
        final boolean idAvailable = entry.id() > 0
                && entries.stream().noneMatch(existing -> existing.id() == entry.id());
        final long id = idAvailable ? entry.id() : nextId;
        entries.add(withId(entry, id));
        save(entries);
    }

    @Override
    public synchronized List<PunishmentHistoryEntry> findByTarget(String target, int page, int pageSize) throws SQLException
    {
        final String normalized = normalizeLookup(target);
        if (normalized.isEmpty())
            return List.of();

        return page(load().stream()
                .filter(entry -> equalsIgnoreCase(entry.targetName(), normalized)
                        || equalsIgnoreCase(entry.targetUuid(), normalized))
                .sorted(NEWEST_FIRST)
                .toList(), page, pageSize);
    }

    @Override
    public synchronized List<PunishmentHistoryEntry> findByIp(String ip, int page, int pageSize) throws SQLException
    {
        final String normalized = normalizeLookup(ip);
        if (normalized.isEmpty())
            return List.of();

        return page(load().stream()
                .filter(entry -> entry.ips().contains(normalized))
                .sorted(NEWEST_FIRST)
                .toList(), page, pageSize);
    }

    @Override
    public synchronized int countByTarget(String target) throws SQLException
    {
        final String normalized = normalizeLookup(target);
        if (normalized.isEmpty())
            return 0;

        return (int) load().stream()
                .filter(entry -> equalsIgnoreCase(entry.targetName(), normalized)
                        || equalsIgnoreCase(entry.targetUuid(), normalized))
                .count();
    }

    @Override
    public synchronized int countByIp(String ip) throws SQLException
    {
        final String normalized = normalizeLookup(ip);
        if (normalized.isEmpty())
            return 0;

        return (int) load().stream()
                .filter(entry -> entry.ips().contains(normalized))
                .count();
    }

    @Override
    public synchronized List<PunishmentHistoryEntry> findCountable(String targetUuid, long since) throws SQLException
    {
        return load().stream()
                .filter(entry -> equalsIgnoreCase(entry.targetUuid(), targetUuid))
                .filter(entry -> entry.appliedAt() >= since)
                .filter(entry -> !entry.isRevoked())
                .filter(entry -> entry.source() != null && entry.source().startsWith("manual-"))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public synchronized Optional<PunishmentHistoryEntry> findById(long id) throws SQLException
    {
        return load().stream()
                .filter(entry -> entry.id() == id)
                .findFirst();
    }

    @Override
    public synchronized boolean revoke(long id, String revokedBy, long revokedAt) throws SQLException
    {
        final List<PunishmentHistoryEntry> entries = load();
        for (int index = 0; index < entries.size(); index++)
        {
            final PunishmentHistoryEntry entry = entries.get(index);
            if (entry.id() == id && !entry.isRevoked())
            {
                entries.set(index, withRevocation(entry, revokedBy, revokedAt));
                save(entries);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean revokeMatching(PunishmentHistoryEntry expected, String revokedBy, long revokedAt)
            throws SQLException
    {
        final List<PunishmentHistoryEntry> entries = load();
        for (int index = 0; index < entries.size(); index++)
        {
            final PunishmentHistoryEntry entry = entries.get(index);
            if (!entry.isRevoked() && isSamePunishment(entry, expected))
            {
                entries.set(index, withRevocation(entry, revokedBy, revokedAt));
                save(entries);
                return true;
            }
        }
        return false;
    }

    private List<PunishmentHistoryEntry> load() throws SQLException
    {
        if (!file.exists())
            return new ArrayList<>();

        try (FileReader reader = new FileReader(file))
        {
            final List<PunishmentHistoryEntry> entries = JsonUtil.GSON.fromJson(reader, ENTRY_LIST_TYPE);
            return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
        catch (IOException | JsonParseException ex)
        {
            throw new SQLException("Could not read punishment-history.json.", ex);
        }
    }

    private void save(List<PunishmentHistoryEntry> entries) throws SQLException
    {
        final File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory())
            throw new SQLException("Could not create the punishment history data directory.");

        try (FileWriter writer = new FileWriter(file))
        {
            JsonUtil.GSON.toJson(entries, ENTRY_LIST_TYPE, writer);
        }
        catch (IOException ex)
        {
            throw new SQLException("Could not write punishment-history.json.", ex);
        }
    }

    private static List<PunishmentHistoryEntry> page(List<PunishmentHistoryEntry> entries, int page, int pageSize)
    {
        final int start = Math.max(0, (page - 1) * pageSize);
        if (start >= entries.size())
            return List.of();
        return entries.subList(start, Math.min(entries.size(), start + pageSize));
    }

    private static boolean equalsIgnoreCase(String left, String right)
    {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean isSamePunishment(PunishmentHistoryEntry left, PunishmentHistoryEntry right)
    {
        return left.appliedAt() == right.appliedAt()
                && Objects.equals(left.targetUuid(), right.targetUuid())
                && Objects.equals(left.targetName(), right.targetName())
                && Objects.equals(left.actorUuid(), right.actorUuid())
                && Objects.equals(left.actorName(), right.actorName())
                && Objects.equals(left.punishmentType(), right.punishmentType())
                && Objects.equals(left.reason(), right.reason())
                && Objects.equals(left.expiryAt(), right.expiryAt())
                && Objects.equals(left.source(), right.source());
    }

    private static PunishmentHistoryEntry withId(PunishmentHistoryEntry entry, long id)
    {
        return entryWith(entry, id, entry.revokedAt(), entry.revokedBy());
    }

    private static PunishmentHistoryEntry withRevocation(PunishmentHistoryEntry entry, String revokedBy, long revokedAt)
    {
        return entryWith(entry, entry.id(), revokedAt, revokedBy);
    }

    private static PunishmentHistoryEntry entryWith(PunishmentHistoryEntry entry, long id, Long revokedAt, String revokedBy)
    {
        return new PunishmentHistoryEntry(id, entry.targetUuid(), entry.targetName(), entry.actorUuid(),
                entry.actorName(), entry.punishmentType(), entry.reason(), entry.appliedAt(), entry.expiryAt(),
                entry.source(), entry.ips(), revokedAt, revokedBy);
    }

    private static String normalizeLookup(String value)
    {
        return value == null ? "" : value.trim();
    }
}
