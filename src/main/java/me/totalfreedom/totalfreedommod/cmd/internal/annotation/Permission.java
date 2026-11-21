package me.totalfreedom.totalfreedommod.cmd.internal.annotation;

import java.lang.annotation.*;

import me.totalfreedom.totalfreedommod.cmd.SourceType;

/**
 * Declares who may run a command (or a single subcommand handler) and from where.
 * <p>
 * Place on an {@code FCommand} class. The check is also wired into the Brigadier root node's
 * {@code requires()}, so unauthorized senders don't see the command in tab completion. Place on a
 * handler method to guard that specific subcommand, which overrides the class-level declaration.
 * <p>
 * A declaration names only the internal permission node it needs; it never names a rank. The rank
 * required to reach a handler is derived from {@code ranks.json} by resolving which is the least
 * privileged rank that grants the node, so re-tiering a command is a config edit rather than a code
 * change. Two consequences follow, and both matter when adding a node:
 * <ul>
 *   <li>A handler that must be stricter than its parent needs its own <em>distinct</em> node. A node
 *       shared with the parent resolves to the same rank for both, silently dropping the raise.</li>
 *   <li>A node no rank grants is unreachable rather than open. A typo therefore denies everyone,
 *       which is the safe direction, but it will not surface until someone tries the command.</li>
 * </ul>
 * These nodes are internal to TFM and are deliberately never registered as Bukkit permissions:
 * every player on a TotalFreedom server is opped, so a Bukkit node would effectively grant itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface Permission
{
    SourceType source() default SourceType.BOTH;

    String permission();

    String message() default "You do not have permission to use this command.";
}
