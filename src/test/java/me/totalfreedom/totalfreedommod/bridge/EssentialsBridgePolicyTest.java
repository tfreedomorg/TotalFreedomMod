package me.totalfreedom.totalfreedommod.bridge;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialsBridgePolicyTest
{
    private static final UUID FIRST_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @ParameterizedTest
    @CsvSource({
            "'/tpa Wade',tpa,Wade",
            "'/TPAHERE Wade_Smith',tpahere,Wade_Smith",
            "'/essentials:tpa 00000000-0000-0000-0000-000000000001',essentials:tpa,00000000-0000-0000-0000-000000000001",
            "'   /etpa    player_name   ',etpa,player_name"
    })
    void parsesPotentialTeleportRequestCommands(
            final String input,
            final String expectedLabel,
            final String expectedTarget)
    {
        final Optional<EssentialsBridge.TeleportRequestCommand> parsed =
                EssentialsBridge.parseTeleportRequestCommand(input);

        assertTrue(parsed.isPresent());
        assertEquals(expectedLabel, parsed.orElseThrow().label());
        assertEquals(expectedTarget, parsed.orElseThrow().target());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/tpa", "/tpa one two", "tpa Wade"})
    void rejectsMalformedTeleportRequestCommands(final String input)
    {
        assertTrue(EssentialsBridge.parseTeleportRequestCommand(input).isEmpty());
    }

    @Test
    void usesTheGraceAwareSeparationLookupOnce()
    {
        final int[] lookups = {0};
        final BiPredicate<UUID, UUID> separated = (first, second) ->
        {
            lookups[0]++;
            return first.equals(FIRST_PLAYER) && second.equals(SECOND_PLAYER);
        };

        assertTrue(EssentialsBridge.shouldCancelSeparatedContact(
                FIRST_PLAYER,
                SECOND_PLAYER,
                separated));
        assertEquals(1, lookups[0]);
        assertFalse(EssentialsBridge.shouldCancelSeparatedContact(
                SECOND_PLAYER,
                FIRST_PLAYER,
                separated));
        assertEquals(2, lookups[0]);
    }
}
