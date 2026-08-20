package me.totalfreedom.totalfreedommod.ssh;

import java.util.Set;
import java.util.UUID;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;

/**
 * Wraps a console-class {@link CommandSender} so that remote channels (SSH, Discord) can
 * attribute output to the identity that issued the command, while every other call is
 * forwarded to the real sender.
 * <p>
 * <b>Never hand this wrapper to {@code Server#dispatchCommand}.
 */
public final class AttributedConsoleSender implements CommandSender, ForwardingAudience.Single
{

    private final CommandSender delegate;
    private final String displayName;

    public AttributedConsoleSender(final CommandSender delegate, final String displayName)
    {
        this.delegate = delegate;
        this.displayName = displayName;
    }

    /**
     * Peels off any attribution wrapper so the result is a sender CraftBukkit recognises.
     * Safe to call on senders that were never wrapped.
     */
    public static CommandSender unwrap(final CommandSender sender)
    {
        CommandSender current = sender;
        while (current instanceof AttributedConsoleSender attributed)
        {
            current = attributed.getDelegate();
        }
        return current;
    }

    public CommandSender getDelegate()
    {
        return delegate;
    }

    @Override
    public Audience audience()
    {
        return delegate;
    }

    @Override
    public void sendMessage(final Component message)
    {
        delegate.sendMessage(message);
    }

    @Override
    public void sendMessage(final Component message, final ChatType.Bound boundChatType)
    {
        delegate.sendMessage(message, boundChatType);
    }

    @Override
    public void sendMessage(final SignedMessage signedMessage, final ChatType.Bound boundChatType)
    {
        delegate.sendMessage(signedMessage, boundChatType);
    }

    @Override
    public String getName()
    {
        return displayName;
    }

    @Override
    public Component name()
    {
        return Component.text(displayName);
    }

    @Override
    public void sendMessage(final String message)
    {
        delegate.sendMessage(message);
    }

    @Override
    public void sendMessage(final String... messages)
    {
        delegate.sendMessage(messages);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void sendMessage(final UUID sender, final String message)
    {
        delegate.sendMessage(sender, message);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void sendMessage(final UUID sender, final String... messages)
    {
        delegate.sendMessage(sender, messages);
    }

    @Override
    public Server getServer()
    {
        return delegate.getServer();
    }

    @Override
    public Spigot spigot()
    {
        return delegate.spigot();
    }

    @Override
    public boolean isPermissionSet(final String name)
    {
        return delegate.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(final Permission perm)
    {
        return delegate.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(final String name)
    {
        return delegate.hasPermission(name);
    }

    @Override
    public boolean hasPermission(final Permission perm)
    {
        return delegate.hasPermission(perm);
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin plugin, final String name, final boolean value)
    {
        return delegate.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin plugin)
    {
        return delegate.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin plugin, final String name, final boolean value, final int ticks)
    {
        return delegate.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(final Plugin plugin, final int ticks)
    {
        return delegate.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(final PermissionAttachment attachment)
    {
        delegate.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions()
    {
        delegate.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions()
    {
        return delegate.getEffectivePermissions();
    }

    @Override
    public boolean isOp()
    {
        return delegate.isOp();
    }

    @Override
    public void setOp(final boolean value)
    {
        delegate.setOp(value);
    }
}
