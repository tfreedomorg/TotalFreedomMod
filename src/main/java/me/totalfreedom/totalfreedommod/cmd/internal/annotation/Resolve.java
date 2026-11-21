package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

/**
 * Routes a handler parameter through a custom argument resolver.
 * <p>
 * Parameters whose type has a registered resolver (e.g. {@code Material},
 * {@code Enchantment}, {@code EntityType}, {@code PotionEffectType}, {@code Key},
 * {@code OfflinePlayer}) are resolved automatically without this annotation; use it to:
 * <ul>
 *   <li>pass a resolution {@link #strategy()} (e.g. {@code "blocks"}/{@code "items"} for
 *       Material, {@code "alive"}/{@code "spawnable"}/{@code "mobs"} for EntityType,
 *       {@code "hideUnknownPlayers"} for OfflinePlayer), or
 *   <li>name a resolver explicitly via {@link #value()} when the parameter type alone is
 *       ambiguous (e.g. {@code @Resolve("MaterialQuery")} on a {@code List<Material>} parameter).
 * </ul>
 * The argument is captured as a single word by Brigadier and handed to the resolver at
 * dispatch time; a thrown {@code ArgumentResolutionException} aborts the handler and
 * messages the sender.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Resolve
{
    /**
     * Resolver name as returned by {@code AbstractArgumentResolver.name()}.
     * Leave empty to look the resolver up by the parameter's type.
     */
    String value() default "";

    /**
     * Optional resolver-specific strategy hint.
     */
    String strategy() default "";
}
