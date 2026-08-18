package me.totalfreedom.totalfreedommod.player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the team-packet redaction modes.
 * <p>
 * {@code REMOVE_ENTITIES} is the important case: it was originally left unredacted, so when a
 * blocked player quit, the rank team cleanup broadcast a removal for an entry the blocker's
 * client had never been told about. A vanilla client throws out of
 * {@code Scoreboard#removePlayerFromTeam} in that situation and drops the connection with a
 * network protocol error, which crashed the blocker rather than the blocked player.
 */
class PlayerBlockEnforcerTeamRedactionTest
{
    @ParameterizedTest
    @EnumSource(value = TeamMode.class, names = {"CREATE", "ADD_ENTITIES", "REMOVE_ENTITIES"})
    void redactsEveryModeThatCarriesAnEntryList(final TeamMode mode)
    {
        assertTrue(PlayerBlockEnforcer.redactsBlockedTeamEntries(mode));
    }

    @ParameterizedTest
    @EnumSource(value = TeamMode.class, names = {"REMOVE", "UPDATE"})
    void leavesWholeTeamAndMetadataOnlyModesUntouched(final TeamMode mode)
    {
        assertFalse(PlayerBlockEnforcer.redactsBlockedTeamEntries(mode));
    }
}
