package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBlockPaginationTest
{
    @Test
    void pageCountAlwaysHasOnePageForAnEmptyList()
    {
        assertEquals(1, Command_block.pageCount(0));
    }

    @Test
    void paginationRetainsEveryEntryWithoutACap()
    {
        final List<String> names = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> String.format("Player%02d", index))
                .toList();

        assertEquals(3, Command_block.pageCount(names.size()));
        assertEquals(names.subList(0, 10), Command_block.pageItems(names, 1));
        assertEquals(names.subList(10, 20), Command_block.pageItems(names, 2));
        assertEquals(names.subList(20, 25), Command_block.pageItems(names, 3));
    }

    @Test
    void pageValidationRejectsZeroAndPagesPastTheEnd()
    {
        assertFalse(Command_block.isValidPage(0, 11));
        assertTrue(Command_block.isValidPage(1, 11));
        assertTrue(Command_block.isValidPage(2, 11));
        assertFalse(Command_block.isValidPage(3, 11));
    }
}
