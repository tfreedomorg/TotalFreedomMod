package me.totalfreedom.totalfreedommod.rank;

import java.util.Optional;

/**
 * Resolves the rank held by some kind of actor.
 * <p>
 * Rank is earned differently depending on what is asking. A player carries an admin profile; an SSH
 * session carries a proven public key; an RCON channel carries no identity at all and falls back to
 * what {@code host_senders:} bound it to. Expressing each of those as a resolver over its own
 * subject type keeps the rules for one kind of actor in one place, instead of as another branch in
 * a single method that has to know about all of them at once.
 *
 * @param <T> the kind of actor this resolver understands
 */
@FunctionalInterface
public interface RankResolver<T>
{

    /**
     * The rank {@code subject} holds, or empty when this resolver cannot place it. Empty is a
     * routine answer rather than an error: it means "not mine to answer", and the caller is
     * expected to fall through to another resolver or to a floor.
     */
    Optional<CustomRank> resolve(T subject);

    /**
     * A resolver that consults this one first and falls back to {@code next} when it declines.
     */
    default RankResolver<T> orElse(final RankResolver<T> next)
    {
        return subject -> resolve(subject).or(() -> next.resolve(subject));
    }

}
