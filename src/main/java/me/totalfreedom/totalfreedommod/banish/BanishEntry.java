package me.totalfreedom.totalfreedommod.banish;

public record BanishEntry(String ip, String username, String issuedBy, String reason, long createdAt, long expiresAt)
{
}
