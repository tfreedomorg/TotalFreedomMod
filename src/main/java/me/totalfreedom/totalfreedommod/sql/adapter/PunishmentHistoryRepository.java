package me.totalfreedom.totalfreedommod.sql.adapter;

import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;

import java.sql.SQLException;
import java.util.List;

public interface PunishmentHistoryRepository
{
    void recordPunishment(PunishmentHistoryEntry entry) throws SQLException;

    List<PunishmentHistoryEntry> findByTarget(String target, int page, int pageSize) throws SQLException;

    List<PunishmentHistoryEntry> findByIp(String ip, int page, int pageSize) throws SQLException;

    int countByTarget(String target) throws SQLException;

    int countByIp(String ip) throws SQLException;
}
