package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

/**
 * Overrides tab-completion for a specific argument position on a subcommand.
 * <p>
 * {@link #value()} must match the {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand#value() Subcommand#value()} path of the handler this
 * completer applies to (a Completer is always a separate method from the handler, so it needs an explicit link). 
 * {@link #position()} is zero-based and counts only the handler's positional (Brigadier argument)
 * parameters: the sender and any {@link Switch}-annotated parameters are excluded, since switches
 * become literal branches rather than argument nodes.
 * <p>
 * The annotated method must return {@code List<String>} and accept the same sender type as the
 * handler, followed by the partially-typed input ({@code String}), and optionally a third
 * {@code List<String>} parameter receiving the text already typed for the preceding positional
 * arguments (see {@link #position()} for what counts as one). That third parameter is how a
 * completer whose candidates depend on an earlier argument gets at it: {@code /rankconfig set
 * <rank> <property> <value>} completes {@code value} differently per {@code property}.
 * The returned list is used as is. Consider {@link me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch#filter FuzzyMatch#filter(List<String>, String)}
 * for subsequence fuzzy matching against a candidate list, which also happens to be automatically applied to enum-typed arguments with no {@code @Completer}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Completer
{
    String value();
    int position();

    /**
     * What a completer is shown and what its suggestions overwrite, for an argument that holds the
     * rest of the line.
     * <p>
     * The two are worth choosing separately because a suggestion is displayed as the text it would
     * replace: widening the replacement to the whole argument also makes the popup spell out the 
     * whole argument, which ready badly when only the last word is really being completed.
     */
    enum Scope
    {
        /**
         * The word under the cursor, replacing just that word. Right for free text that merely
         * mentions completable things, e.g. a chat message naming a player.
         */
        WORD,

        /**
         * The whole argument, replacing all of it. Right when the argument is a single value that
         * happens to tolerate spaces, e.g. a nickname.
         */
        ARGUMENT,

        /**
         * The whole argument, replacing only its final word. Right when the argument is itself
         * structured, e.g. a command line whose completions depend on the words before the one
         * being typed.
         */
        ARGUMENT_TO_WORD
    }

    /**
     * How much of a {@link Greedy} argument the completer works on. Ignored for non-greedy
     * arguments, which are one word wide already.
     */
    Scope scope() default Scope.WORD;
}
