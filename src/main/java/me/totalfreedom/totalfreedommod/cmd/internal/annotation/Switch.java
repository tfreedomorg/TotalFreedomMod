package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

/**
 * Marks a {@code boolean} handler parameter as an optional command-line switch (e.g. {@code -s}).
 * <p>
 * Replaces the legacy {@code switches=} pattern attribute. Switch parameters are excluded from
 * the positional Brigadier argument chain; instead, the processor generates one literal branch
 * per subset of a handler's switches, in the order they are declared on the method, each leading
 * into the same positional arguments. Whether each literal was present in the invocation is
 * passed as {@code true}/{@code false} to the corresponding parameter.
 * <p>
 * Switches must appear as a contiguous prefix in declaration order (e.g. {@code -s -nrb}); unlike
 * the legacy system, they cannot be reordered or interspersed with positional arguments.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface Switch
{
    /**
     * The flag token, without the leading dash (e.g. {@code "s"} for {@code -s}).
     */
    String value();
}
