package me.totalfreedom.totalfreedommod.player;

import me.totalfreedom.totalfreedommod.framework.FatalServiceStartupException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the fail-closed PacketEvents availability check used by
 * {@link PlayerBlockEnforcer#registerBlockedIdentityPacketListener()}. The block feature's
 * system chat, team, and score privacy filtering depends entirely on that listener, so startup
 * must refuse to continue rather than silently fall back to partial privacy.
 * <p>
 * This only checks plugin presence, not Bukkit's {@code isEnabled()} flag: TotalFreedomMod is a
 * Paper-format plugin and PacketEvents is a classic Bukkit-format plugin, and on some Paper
 * builds their onEnable() calls are not reliably ordered against each other even with a
 * declared "load: BEFORE" dependency. PacketEvents publishes its static API from its own
 * onLoad(), so it is safe to register against as soon as the plugin is present; a genuinely
 * unavailable PacketEvents still fails closed via the try/catch around the registration call
 * itself, not this presence check.
 */
class PlayerBlockEnforcerPacketEventsAvailabilityTest
{
    @Test
    void allowsStartupWhenPacketEventsIsPresent()
    {
        assertDoesNotThrow(() -> PlayerBlockEnforcer.validatePacketEventsAvailability(true));
    }

    @Test
    void failsClosedWhenPacketEventsPluginIsMissing()
    {
        assertThrows(
                FatalServiceStartupException.class,
                () -> PlayerBlockEnforcer.validatePacketEventsAvailability(false));
    }
}
