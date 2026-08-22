package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Subcommand
{
    String value();
    String description() default "";
}
