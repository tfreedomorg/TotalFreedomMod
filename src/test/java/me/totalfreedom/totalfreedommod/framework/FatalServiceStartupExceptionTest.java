package me.totalfreedom.totalfreedommod.framework;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FatalServiceStartupExceptionTest
{
    @Test
    void messageOnlyConstructorHasNoCause()
    {
        final FatalServiceStartupException ex = new FatalServiceStartupException("boom");

        assertEquals("boom", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void causeConstructorPreservesTheOriginalFailure()
    {
        final RuntimeException cause = new RuntimeException("original failure");

        final FatalServiceStartupException ex = new FatalServiceStartupException("boom", cause);

        assertEquals("boom", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
