package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

/**
 * Marks the final handler parameter as variable-length.
 * <p>
 * Replaces the legacy {@code <arg..>} pattern syntax. Only valid on the last parameter of a handler. 
 * The parameter must be a {@code String} or be paired with {@link Resolve} for post-processing of the raw remainder.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Greedy
{
}
