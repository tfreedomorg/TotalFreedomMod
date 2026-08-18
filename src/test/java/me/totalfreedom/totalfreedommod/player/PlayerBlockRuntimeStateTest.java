package me.totalfreedom.totalfreedommod.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PlayerBlockRuntimeStateTest
{
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final AtomicLong clock = new AtomicLong();
    private final PlayerBlockRuntimeState state = new PlayerBlockRuntimeState(
            clock::get,
            Duration.ofSeconds(10),
            Duration.ofSeconds(5));

    @Test
    void separationGraceIsUnorderedAndExpiresAtTheDeadline()
    {
        state.beginSeparationGrace(ALICE, BOB);

        assertTrue(state.isInSeparationGrace(ALICE, BOB));
        assertTrue(state.isInSeparationGrace(BOB, ALICE));

        clock.set(Duration.ofSeconds(10).toNanos() - 1L);
        assertTrue(state.isInSeparationGrace(ALICE, BOB));

        clock.incrementAndGet();
        assertFalse(state.isInSeparationGrace(ALICE, BOB));
        assertFalse(state.isInSeparationGrace(BOB, ALICE));
    }

    @Test
    void separationGraceCanBeClearedForAForcedUnblock()
    {
        state.beginSeparationGrace(ALICE, BOB);

        state.clearSeparationGrace(BOB, ALICE);

        assertFalse(state.isInSeparationGrace(ALICE, BOB));
    }

    @Test
    void temporaryRevealIsDirectedAndAnExtensionInvalidatesTheOldDeadline()
    {
        final long firstDeadline = state.beginTemporaryReveal(ALICE, BOB);

        assertTrue(state.isTemporarilyRevealed(ALICE, BOB));
        assertFalse(state.isTemporarilyRevealed(BOB, ALICE));

        clock.set(Duration.ofSeconds(2).toNanos());
        final long extendedDeadline = state.beginTemporaryReveal(ALICE, BOB);

        clock.set(firstDeadline);
        assertFalse(state.expireTemporaryReveal(ALICE, BOB, firstDeadline));
        assertTrue(state.isTemporarilyRevealed(ALICE, BOB));

        clock.set(extendedDeadline);
        assertTrue(state.expireTemporaryReveal(ALICE, BOB, extendedDeadline));
        assertFalse(state.isTemporarilyRevealed(ALICE, BOB));
    }

    @Test
    void generalCleanupCannotConsumeARevealBeforeItsExpiryTask()
    {
        final long deadline = state.beginTemporaryReveal(ALICE, BOB);
        clock.set(deadline);

        state.pruneExpired();

        assertTrue(state.expireTemporaryReveal(ALICE, BOB, deadline));
    }

    @Test
    void identityRedactionIsCaseInsensitiveAndUsesPlayerNameBoundaries()
    {
        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(
                Component.text("Wade WadeSmith preWade @wAdE wade_ Wade! THE WADE"),
                List.of("Wade", "The Wade"));

        assertEquals(
                "[blocked player] WadeSmith preWade [blocked player] wade_ [blocked player]! [blocked player]",
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void identityRedactionPreservesTheMatchedComponentStyle()
    {
        final Component message = Component.text("Hello ")
                .append(Component.text("@WaDe", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/msg Wade"))
                        .hoverEvent(HoverEvent.showText(Component.text("Wade")))
                        .insertion("Wade"))
                .append(Component.text("!"));

        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(message, List.of("Wade"));
        final TextComponent replacement = findText(redacted, "[blocked player]");

        assertEquals(NamedTextColor.GOLD, replacement.color());
        assertEquals(TextDecoration.State.TRUE, replacement.decoration(TextDecoration.BOLD));
        assertNull(replacement.clickEvent());
        assertNull(replacement.hoverEvent());
        assertNull(replacement.insertion());
    }

    @Test
    void identityRedactionCannotBeEvadedByFormattingEveryCharacter()
    {
        final Component message = Component.text("W", NamedTextColor.RED)
                .append(Component.text("a", NamedTextColor.GOLD))
                .append(Component.text("d", NamedTextColor.YELLOW))
                .append(Component.text("e", NamedTextColor.GREEN));

        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(message, List.of("Wade"));

        assertEquals(
                PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER,
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void identityRedactionHandlesInvisibleAndCanonicalUnicodeVariants()
    {
        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(
                Component.text("W\u200Ba\u200Bde Jos\u0065\u0301"),
                List.of("Wade", "Jos\u00E9"));

        assertEquals(
                "[blocked player] [blocked player]",
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void identityRedactionUsesTheRenderedTildeForUserControlledNicknames()
    {
        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(
                Component.text("~SecretNick SecretNick"),
                List.of("~SecretNick"));

        assertEquals(
                "[blocked player] SecretNick",
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void shortNicknamesRemainPrivateOnlyWithTheirRenderedTilde()
    {
        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(
                Component.text("~X X ~AB AB"),
                List.of("~X", "~AB"));

        assertEquals(
                "[blocked player] X [blocked player] AB",
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void identityRedactionHandlesRepeatedMentionsAndUnicodeCaseFolding()
    {
        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(
                Component.text("@@Wade Ος A\u034Flice"),
                List.of("Wade", "ΟΣ", "Alice"));

        assertEquals(
                "[blocked player] [blocked player] [blocked player]",
                PlainTextComponentSerializer.plainText().serialize(redacted));
    }

    @Test
    void targetUuidIsAlsoTreatedAsAVisibleIdentity()
    {
        final BlockedIdentityFilter filter = new BlockedIdentityFilter();
        filter.replaceTarget(BOB, List.of("Wade"));

        assertEquals(
                "Player [blocked player]",
                PlainTextComponentSerializer.plainText().serialize(
                        filter.redact(Component.text(String.format("Player %s", BOB)))));
    }

    @Test
    void aNewCanonicalOwnerCannotInheritAnotherTargetsStaleAlias()
    {
        final BlockedIdentityFilter filter = new BlockedIdentityFilter();
        filter.replaceTarget(BOB, List.of("Alice"));

        filter.removeAliasFromOtherTargets(ALICE, "Alice");

        assertEquals("Alice", PlainTextComponentSerializer.plainText().serialize(
                filter.redact(Component.text("Alice"))));
        assertEquals(PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER,
                PlainTextComponentSerializer.plainText().serialize(
                        filter.redact(Component.text(BOB.toString()))));
    }

    @Test
    void canonicalOwnershipAlsoRemovesAStaleRenderedNickname()
    {
        final BlockedIdentityFilter filter = new BlockedIdentityFilter();
        filter.replaceTarget(BOB, List.of("~Alice"));

        filter.removeAliasFromOtherTargets(ALICE, "~Alice");

        assertEquals("~Alice", PlainTextComponentSerializer.plainText().serialize(
                filter.redact(Component.text("~Alice"))));
    }

    @Test
    void collisionChecksUseTheSameUnicodeCanonicalizationAsRedaction()
    {
        assertEquals(
                BlockedIdentityFilter.normalizedComparableIdentity("Jose"),
                BlockedIdentityFilter.normalizedComparableIdentity("~JOSÉ"));
    }

    @Test
    void identityMetadataIsRemovedEvenWhenVisibleTextDoesNotContainTheName()
    {
        final Component message = Component.text("Message player")
                .clickEvent(ClickEvent.suggestCommand("/msg Wade "))
                .hoverEvent(HoverEvent.showText(Component.text("Wade")));

        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(message, List.of("Wade"));

        assertEquals("Message player", PlainTextComponentSerializer.plainText().serialize(redacted));
        assertNull(redacted.clickEvent());
        assertNull(redacted.hoverEvent());
    }

    @Test
    void identityRedactionTraversesTranslatableArguments()
    {
        final Component message = Component.translatable(
                "chat.type.text",
                Component.text("Wade"),
                Component.text("hello"));

        final Component redacted = PlayerBlockRuntimeState.redactBlockedIdentities(message, List.of("Wade"));
        final TranslatableComponent translated = (TranslatableComponent) redacted;
        final Component redactedName = (Component) translated.arguments().getFirst().value();

        assertEquals(
                PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER,
                PlainTextComponentSerializer.plainText().serialize(redactedName));
    }

    private TextComponent findText(final Component component, final String content)
    {
        if (component instanceof final TextComponent text && text.content().equals(content))
            return text;

        return component.children()
                .stream()
                .map(child -> findText(child, content))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
