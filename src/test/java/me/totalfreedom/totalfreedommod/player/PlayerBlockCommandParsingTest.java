package me.totalfreedom.totalfreedommod.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerBlockCommandParsingTest
{
    @Test
    void selectorSnbtWhitespaceRemainsInOneArgument()
    {
        assertArrayEquals(
                new String[]{
                        "minecraft:msg",
                        "@a[nbt={UUID:[I;1, 2, 3, 4]}]",
                        "hello"},
                PlayerBlockEnforcer.tokenizeCommand(
                        "/minecraft:msg @a[nbt={UUID:[I;1, 2, 3, 4]}] hello"));
    }

    @Test
    void quotedAndNestedSelectorTextRemainsIntact()
    {
        assertArrayEquals(
                new String[]{
                        "tp",
                        "@s",
                        "0",
                        "64",
                        "0",
                        "facing",
                        "entity",
                        "@a[nbt={CustomName:'{\"text\": \"Wade Smith\"}'}]",
                        "eyes"},
                PlayerBlockEnforcer.tokenizeCommand(
                        "/tp @s 0 64 0 facing entity @a[nbt={CustomName:'{\"text\": \"Wade Smith\"}'}] eyes"));
    }

    @Test
    void coordinateGrammarDoesNotResolveRelativeCoordinatesAsNicknames()
    {
        assertTrue(PlayerBlockEnforcer.hasCoordinateTriplet(
                PlayerBlockEnforcer.tokenizeCommand("/tp ~ ~1 ~-2.5 facing entity Wade eyes"),
                1));
        assertTrue(PlayerBlockEnforcer.hasCoordinateTriplet(
                PlayerBlockEnforcer.tokenizeCommand("/tp @s ^ ^1 ^-2.5 facing entity Wade eyes"),
                2));
        assertFalse(PlayerBlockEnforcer.hasCoordinateTriplet(
                PlayerBlockEnforcer.tokenizeCommand("/tp @s Wade"),
                1));
    }
}
