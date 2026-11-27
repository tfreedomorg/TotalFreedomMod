package me.totalfreedom.totalfreedommod.banish;

import java.util.UUID;

public record BanishEntry(String ip, String username, String issuedBy, String reason, long createdAt, long expiresAt,
                          UUID uuid)
{
}
