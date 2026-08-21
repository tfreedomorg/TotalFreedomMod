package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface Command
{
    String name();
    String description() default "";
    String usage() default "";
    String[] aliases() default {};
}

