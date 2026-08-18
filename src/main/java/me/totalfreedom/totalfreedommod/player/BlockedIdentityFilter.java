package me.totalfreedom.totalfreedommod.player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Incremental, thread-safe identity matcher for one block-list viewer.
 *
 * <p>The trie avoids rebuilding an unbounded regular expression for every outgoing message.
 * Normalization also makes case, canonical Unicode forms, formatting splits, mentions, and
 * invisible formatting characters ineffective as identity-redaction bypasses.</p>
 */
final class BlockedIdentityFilter
{
    private static final UUID STANDALONE_IDENTITIES = new UUID(0L, 0L);

    private final TrieNode root = new TrieNode();
    private final Map<UUID, Set<String>> identitiesByTarget = new HashMap<>();
    private final Map<String, Set<UUID>> targetsByIdentity = new HashMap<>();
    private final Map<String, Integer> identityReferences = new ConcurrentHashMap<>();
    private volatile int identityCount;

    static BlockedIdentityFilter fromIdentities(final Collection<String> identities)
    {
        final BlockedIdentityFilter filter = new BlockedIdentityFilter();
        filter.replaceTarget(STANDALONE_IDENTITIES, identities);
        return filter;
    }

    static String normalizedComparableIdentity(final String identity)
    {
        Objects.requireNonNull(identity, "identity");
        final String withoutMention = removeLeadingMarker(identity.trim(), '@');
        final String withoutNicknameMarker = removeLeadingMarker(withoutMention, '~');
        return normalize(withoutNicknameMarker).text();
    }

    static String normalizedAliasIdentity(final String identity)
    {
        Objects.requireNonNull(identity, "identity");
        return normalize(removeLeadingMarker(identity.trim(), '@')).text();
    }

    synchronized void replaceTarget(final UUID target, final Collection<String> identities)
    {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(identities, "identities");

        final Set<String> normalizedIdentities = new HashSet<>(normalizeAliases(identities));
        if (!STANDALONE_IDENTITIES.equals(target))
            normalizedIdentities.add(normalize(target.toString()).text());
        final Set<String> previousIdentities = identitiesByTarget.getOrDefault(target, Set.of());
        // Add before removing so concurrent packet readers always see at least one version of a
        // changing nickname. A brief overlap is privacy-safe; a gap is not.
        normalizedIdentities.stream()
                .filter(identity -> !previousIdentities.contains(identity))
                .forEach(identity -> addIdentity(target, identity));
        previousIdentities.stream()
                .filter(identity -> !normalizedIdentities.contains(identity))
                .forEach(identity -> removeIdentity(target, identity));

        if (normalizedIdentities.isEmpty())
            identitiesByTarget.remove(target);
        else
            identitiesByTarget.put(target, Set.copyOf(normalizedIdentities));
    }

    synchronized void removeTarget(final UUID target)
    {
        Objects.requireNonNull(target, "target");
        final Set<String> removedIdentities = identitiesByTarget.remove(target);
        if (removedIdentities != null)
            removedIdentities.forEach(identity -> removeIdentity(target, identity));
    }

    synchronized void removeAliasFromOtherTargets(
            final UUID currentOwner,
            final String alias)
    {
        Objects.requireNonNull(currentOwner, "currentOwner");
        Objects.requireNonNull(alias, "alias");
        final Set<String> normalizedAliases = normalizeAliases(List.of(alias));
        if (normalizedAliases.isEmpty())
            return;

        final Set<UUID> conflictingTargets = new HashSet<>();
        normalizedAliases.stream()
                .map(targetsByIdentity::get)
                .filter(Objects::nonNull)
                .forEach(conflictingTargets::addAll);
        conflictingTargets.remove(currentOwner);
        conflictingTargets.forEach(target ->
        {
            final Set<String> retainedAliases = new HashSet<>(identitiesByTarget.getOrDefault(target, Set.of()));
            retainedAliases.removeAll(normalizedAliases);
            replaceTarget(target, retainedAliases);
        });
    }

    boolean isEmpty()
    {
        return identityCount == 0;
    }

    boolean matchesExact(final Component component)
    {
        Objects.requireNonNull(component, "component");
        return matchesExact(componentText(component));
    }

    Component redact(final Component component)
    {
        Objects.requireNonNull(component, "component");
        if (isEmpty())
            return component;

        final AtomicBoolean visibleTextChanged = new AtomicBoolean();
        Component redacted = redactTextNodes(component, visibleTextChanged);
        final String remainingText = componentText(redacted);
        if (containsIdentity(remainingText))
        {
            // Component replacement cannot span differently formatted nodes or every component
            // subtype. Flatten only this evasive case so styling cannot split an identity.
            redacted = Component.text(redactPlainText(componentText(component)));
            visibleTextChanged.set(true);
        }

        if (!visibleTextChanged.get() && !metadataContainsIdentity(component))
            return component;

        // Identity-bearing hover, click, and insertion data can disclose the original account
        // even after the visible text is replaced. Visual styling is retained; interaction data
        // is removed from the affected message tree.
        return stripInteractiveMetadata(redacted);
    }

    private synchronized void addIdentity(final UUID target, final String identity)
    {
        targetsByIdentity
                .computeIfAbsent(identity, ignored -> new HashSet<>())
                .add(target);
        final int references = identityReferences.getOrDefault(identity, 0);
        identityReferences.put(identity, references + 1);
        if (references > 0)
            return;

        TrieNode node = root;
        final int[] codePoints = identity.codePoints().toArray();
        for (final int codePoint : codePoints)
        {
            node = node.children.computeIfAbsent(codePoint, ignored -> new TrieNode());
        }
        node.terminal = true;
        identityCount++;
    }

    private synchronized void removeIdentity(final UUID target, final String identity)
    {
        final Set<UUID> targets = targetsByIdentity.get(identity);
        if (targets != null)
        {
            targets.remove(target);
            if (targets.isEmpty())
                targetsByIdentity.remove(identity);
        }

        final Integer references = identityReferences.get(identity);
        if (references == null)
            return;

        if (references > 1)
        {
            identityReferences.put(identity, references - 1);
            return;
        }

        identityReferences.remove(identity);
        final int[] codePoints = identity.codePoints().toArray();
        final List<TrieNode> path = new ArrayList<>(codePoints.length + 1);
        TrieNode node = root;
        path.add(node);
        for (final int codePoint : codePoints)
        {
            node = node.children.get(codePoint);
            if (node == null)
                return;
            path.add(node);
        }

        node.terminal = false;
        identityCount--;
        for (int index = codePoints.length - 1; index >= 0; index--)
        {
            final TrieNode child = path.get(index + 1);
            if (child.terminal || !child.children.isEmpty())
                break;
            path.get(index).children.remove(codePoints[index], child);
        }
    }

    private Set<String> normalizeAliases(final Collection<String> identities)
    {
        final Set<String> normalized = new HashSet<>();
        identities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(identity -> !identity.isBlank())
                .forEach(identity ->
                {
                    final String withoutMention = removeLeadingMarker(identity, '@');
                    addNormalizedAlias(normalized, withoutMention);
                });
        return Set.copyOf(normalized);
    }

    private void addNormalizedAlias(final Set<String> aliases, final String identity)
    {
        final String normalized = normalize(identity).text();
        if (!normalized.isBlank())
            aliases.add(normalized);
    }

    boolean matchesExact(final String candidate)
    {
        final String withoutMention = removeLeadingMarker(candidate.trim(), '@');
        final String normalized = normalize(withoutMention).text();
        return identityReferences.containsKey(normalized);
    }

    private Component redactTextNodes(final Component component, final AtomicBoolean changed)
    {
        Component updated = component;
        if (component instanceof final TextComponent text)
        {
            final String redactedContent = redactPlainText(text.content());
            if (!redactedContent.equals(text.content()))
            {
                updated = text.content(redactedContent);
                changed.set(true);
            }
        }
        else if (component instanceof final TranslatableComponent translatable)
        {
            final List<TranslationArgument> arguments = translatable.arguments()
                    .stream()
                    .map(argument -> redactTranslationArgument(argument, changed))
                    .toList();
            String fallback = translatable.fallback();
            if (fallback != null)
            {
                final String redactedFallback = redactPlainText(fallback);
                if (!redactedFallback.equals(fallback))
                {
                    fallback = redactedFallback;
                    changed.set(true);
                }
            }
            updated = translatable.arguments(arguments).fallback(fallback);
        }
        else if (component instanceof final SelectorComponent selector
                && containsIdentity(selector.pattern()))
        {
            updated = Component.text(PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER)
                    .style(component.style())
                    .children(component.children());
            changed.set(true);
        }
        else if (component instanceof final ScoreComponent score
                && containsIdentity(score.name()))
        {
            updated = Component.text(PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER)
                    .style(component.style())
                    .children(component.children());
            changed.set(true);
        }

        final List<Component> updatedChildren = updated.children()
                .stream()
                .map(child -> redactTextNodes(child, changed))
                .toList();
        if (!updatedChildren.equals(updated.children()))
            updated = updated.children(updatedChildren);
        return updated;
    }

    private TranslationArgument redactTranslationArgument(
            final TranslationArgument argument,
            final AtomicBoolean changed)
    {
        if (!(argument.value() instanceof final Component component))
            return argument;

        final Component redacted = redactTextNodes(component, changed);
        return redacted.equals(component) ? argument : TranslationArgument.component(redacted);
    }

    private String redactPlainText(final String text)
    {
        final List<Match> matches = findMatches(text);
        if (matches.isEmpty())
            return text;

        final StringBuilder redacted = new StringBuilder(text.length());
        int cursor = 0;
        for (final Match match : matches)
        {
            redacted.append(text, cursor, match.start());
            redacted.append(PlayerBlockRuntimeState.BLOCKED_IDENTITY_PLACEHOLDER);
            cursor = match.end();
        }
        redacted.append(text, cursor, text.length());
        return redacted.toString();
    }

    private boolean containsIdentity(final String text)
    {
        return !findMatches(text).isEmpty();
    }

    private List<Match> findMatches(final String text)
    {
        if (text.isEmpty() || isEmpty())
            return List.of();

        final NormalizedText normalized = normalize(text);
        final List<Match> matches = new ArrayList<>();
        int index = 0;
        while (index < normalized.codePoints().length)
        {
            int identityStart = index;
            while (identityStart < normalized.codePoints().length
                    && normalized.codePoints()[identityStart] == '@')
            {
                identityStart++;
            }
            if (identityStart >= normalized.codePoints().length
                    || !hasStartBoundary(normalized.codePoints(), index))
            {
                index++;
                continue;
            }

            TrieNode node = root;
            int longestEnd = -1;
            int cursor = identityStart;
            while (cursor < normalized.codePoints().length)
            {
                node = node.children.get(normalized.codePoints()[cursor]);
                if (node == null)
                    break;
                cursor++;
                if (node.terminal && hasEndBoundary(normalized.codePoints(), cursor))
                    longestEnd = cursor;
            }

            if (longestEnd < 0)
            {
                index++;
                continue;
            }

            final int matchStart = normalized.originalStarts()[index];
            final int matchEnd = extendAcrossIgnoredSuffix(
                    text,
                    normalized.originalEnds()[longestEnd - 1]);
            matches.add(new Match(matchStart, matchEnd));
            index = longestEnd;
        }
        return List.copyOf(matches);
    }

    private int extendAcrossIgnoredSuffix(final String text, final int initialEnd)
    {
        int end = initialEnd;
        while (end < text.length())
        {
            final int codePoint = text.codePointAt(end);
            final int type = Character.getType(codePoint);
            if (!isDefaultIgnorable(codePoint)
                    && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK)
            {
                break;
            }
            end += Character.charCount(codePoint);
        }
        return end;
    }

    private boolean hasStartBoundary(final int[] codePoints, final int index)
    {
        return index == 0 || !isIdentityCharacter(codePoints[index - 1]);
    }

    private boolean hasEndBoundary(final int[] codePoints, final int index)
    {
        return index >= codePoints.length || !isIdentityCharacter(codePoints[index]);
    }

    private boolean isIdentityCharacter(final int codePoint)
    {
        final int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || codePoint == '_'
                || codePoint == '@'
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private boolean metadataContainsIdentity(final Component component)
    {
        final String insertion = component.insertion();
        if (insertion != null && containsIdentity(insertion))
            return true;

        final var clickEvent = component.clickEvent();
        if (clickEvent != null
                && clickEvent.payload() instanceof final ClickEvent.Payload.Text text
                && containsIdentity(text.value()))
            return true;

        final HoverEvent<?> hoverEvent = component.hoverEvent();
        if (hoverEvent != null && hoverValueContainsIdentity(hoverEvent.value()))
            return true;

        if (component instanceof final TranslatableComponent translatable
                && translatable.arguments()
                        .stream()
                        .map(TranslationArgument::value)
                        .filter(Component.class::isInstance)
                        .map(Component.class::cast)
                        .anyMatch(this::metadataContainsIdentity))
        {
            return true;
        }

        return component.children()
                .stream()
                .anyMatch(this::metadataContainsIdentity);
    }

    private boolean hoverValueContainsIdentity(final Object hoverValue)
    {
        if (hoverValue instanceof final Component component)
            return containsIdentity(componentText(component)) || metadataContainsIdentity(component);

        if (hoverValue instanceof final HoverEvent.ShowEntity entity)
        {
            final Component name = entity.name();
            return containsTarget(entity.id())
                    || (name != null && containsIdentity(componentText(name)));
        }

        return false;
    }

    private synchronized boolean containsTarget(final UUID target)
    {
        return identitiesByTarget.containsKey(target);
    }

    private String componentText(final Component component)
    {
        final StringBuilder text = new StringBuilder();
        appendComponentText(component, text);
        return text.toString();
    }

    private void appendComponentText(final Component component, final StringBuilder text)
    {
        if (component instanceof final TextComponent textComponent)
        {
            text.append(textComponent.content());
        }
        else if (component instanceof final TranslatableComponent translatable)
        {
            if (translatable.fallback() != null)
                text.append(translatable.fallback());
            translatable.arguments().forEach(argument ->
            {
                if (argument.value() instanceof final Component argumentComponent)
                    appendComponentText(argumentComponent, text);
                else
                    text.append(argument.value());
            });
        }
        else if (component instanceof final SelectorComponent selector)
        {
            text.append(selector.pattern());
        }
        else if (component instanceof final ScoreComponent score)
        {
            text.append(score.name());
        }
        else if (component instanceof final KeybindComponent keybind)
        {
            text.append(keybind.keybind());
        }

        component.children().forEach(child -> appendComponentText(child, text));
    }

    private Component stripInteractiveMetadata(final Component component)
    {
        Component sanitized = component
                .clickEvent(null)
                .hoverEvent(null)
                .insertion(null);
        if (sanitized instanceof final TranslatableComponent translatable)
        {
            final List<TranslationArgument> sanitizedArguments = translatable.arguments()
                    .stream()
                    .map(argument ->
                    {
                        if (argument.value() instanceof final Component argumentComponent)
                        {
                            return TranslationArgument.component(stripInteractiveMetadata(argumentComponent));
                        }
                        return argument;
                    })
                    .toList();
            sanitized = translatable.arguments(sanitizedArguments);
        }

        final List<Component> sanitizedChildren = sanitized.children()
                .stream()
                .map(this::stripInteractiveMetadata)
                .toList();
        return sanitized.children(sanitizedChildren);
    }

    private static NormalizedText normalize(final String input)
    {
        final StringBuilder normalizedText = new StringBuilder(input.length());
        final List<Integer> normalizedCodePoints = new ArrayList<>();
        final List<Integer> originalStarts = new ArrayList<>();
        final List<Integer> originalEnds = new ArrayList<>();

        int inputIndex = 0;
        while (inputIndex < input.length())
        {
            final int inputCodePoint = input.codePointAt(inputIndex);
            final int inputEnd = inputIndex + Character.charCount(inputCodePoint);
            if (!isDefaultIgnorable(inputCodePoint))
            {
                final String segment = Normalizer.normalize(
                                new String(Character.toChars(inputCodePoint))
                                        .toUpperCase(Locale.ROOT)
                                        .toLowerCase(Locale.ROOT),
                                Normalizer.Form.NFKD);
                int segmentIndex = 0;
                while (segmentIndex < segment.length())
                {
                    final int normalizedCodePoint = segment.codePointAt(segmentIndex);
                    final int type = Character.getType(normalizedCodePoint);
                    if (!isDefaultIgnorable(normalizedCodePoint)
                            && type != Character.NON_SPACING_MARK
                            && type != Character.COMBINING_SPACING_MARK
                            && type != Character.ENCLOSING_MARK)
                    {
                        normalizedText.appendCodePoint(normalizedCodePoint);
                        normalizedCodePoints.add(normalizedCodePoint);
                        originalStarts.add(inputIndex);
                        originalEnds.add(inputEnd);
                    }
                    segmentIndex += Character.charCount(normalizedCodePoint);
                }
            }
            inputIndex = inputEnd;
        }

        return new NormalizedText(
                normalizedText.toString(),
                normalizedCodePoints.stream().mapToInt(Integer::intValue).toArray(),
                originalStarts.stream().mapToInt(Integer::intValue).toArray(),
                originalEnds.stream().mapToInt(Integer::intValue).toArray());
    }

    private static boolean isDefaultIgnorable(final int codePoint)
    {
        return codePoint == 0x00AD
                || codePoint == 0x034F
                || codePoint == 0x061C
                || (codePoint >= 0x115F && codePoint <= 0x1160)
                || (codePoint >= 0x17B4 && codePoint <= 0x17B5)
                || (codePoint >= 0x180B && codePoint <= 0x180F)
                || Character.getType(codePoint) == Character.FORMAT
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || codePoint == 0x3164
                || codePoint == 0xFFA0
                || (codePoint >= 0x1BCA0 && codePoint <= 0x1BCA3)
                || (codePoint >= 0x1D173 && codePoint <= 0x1D17A)
                || (codePoint >= 0xE0000 && codePoint <= 0xE0FFF);
    }

    private static String removeLeadingMarker(final String value, final char marker)
    {
        String trimmed = value.trim();
        while (!trimmed.isEmpty() && trimmed.charAt(0) == marker)
        {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static final class TrieNode
    {
        private final Map<Integer, TrieNode> children = new ConcurrentHashMap<>();
        private volatile boolean terminal;
    }

    private record Match(int start, int end)
    {
    }

    private record NormalizedText(
            String text,
            int[] codePoints,
            int[] originalStarts,
            int[] originalEnds)
    {
    }
}
