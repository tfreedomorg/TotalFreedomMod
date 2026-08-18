package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerVisibilityPolicyTest
{
    @Test
    void ordinaryPlayersCannotExposeHiddenTargets()
    {
        assertFalse(PlayerVisibilityPolicy.shouldExpose(false, false, false));
    }

    @Test
    void visibleTargetsRemainAvailable()
    {
        assertTrue(PlayerVisibilityPolicy.shouldExpose(false, true, false));
    }

    @Test
    void activeStaffBypassVisibilityFiltering()
    {
        assertTrue(PlayerVisibilityPolicy.shouldExpose(true, false, false));
    }

    @Test
    void commandSpecificHiddenTargetExceptionsRemainAvailable()
    {
        assertTrue(PlayerVisibilityPolicy.shouldExpose(false, false, true));
    }

    @Test
    void commaSeparatedResolversRejectAListContainingAnyHiddenTarget()
    {
        final List<Boolean> targetVisibility = List.of(true, false, true);

        assertFalse(targetVisibility.stream()
                .allMatch(visible -> PlayerVisibilityPolicy.shouldExpose(false, visible, false)));
    }
}
