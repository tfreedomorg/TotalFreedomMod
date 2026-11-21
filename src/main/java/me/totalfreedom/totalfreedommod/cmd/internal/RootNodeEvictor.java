package me.totalfreedom.totalfreedommod.cmd.internal;

import java.lang.reflect.Method;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Frees a root literal on the server command dispatcher so a TFM command can claim the label.
 * 
 * <p>Paper's registrar only replaces a node it recognizes as vanilla. When the label is already
 * held by a plugin, it skips ours instead and the label keeps pointing at the other plugin.
 * Since e.g. Essentials loads before TFM, TFM can never win a contested label on its own;
 * evicting first makes the following registration unconditional.
 * 
 * <p>The dispatcher root is Paper's {@code ApiMirrorRootNode}, which overrides {@code getChild}
 * to consult a live proxy onto Bukkit's command map as well as Brigadier's own child maps.
 * Clearing those maps directly therefore does not remove a {@code plugin.yml} command. Paper
 * declares {@code removeCommand(String)} on that node for exactly this purpose, but it is not
 * part of the compiled API, so it is invoked reflectively and the class is looked up from the
 * live root instance.
 */
public final class RootNodeEvictor
{

    private static volatile Class<?> resolvedFor;
    private static volatile Method removeCommand;

    private RootNodeEvictor() {}

    /**
     * @return the class name of whatever currently owns {@code label}, or null if the label is free
     */
    public static String owner(Commands registrar, String label)
    {
        final CommandNode<CommandSourceStack> existing = registrar.getDispatcher().getRoot().getChild(label);
        return existing == null ? null : existing.getClass().getName();
    }

    /**
     * Removes {@code label} from the dispatcher root if anything currently owns it.
     * 
     * @return true if the label was owned and is now free
     */
    public static boolean evict(Commands registrar, String label)
    {
        final RootCommandNode<CommandSourceStack> root = registrar.getDispatcher().getRoot();

        if (root.getChild(label) == null)
            return false;

        final Method remove = removeCommandMethod(root);
        if (remove == null)
            return false;

        try
        {
            remove.invoke(root, label);
        }
        catch (Exception ex)
        {
            FLog.warning(String.format("Could not remove /%s from the dispatcher root: %s", label, ex.getMessage()));
            return false;
        }

        return root.getChild(label) == null;
    }

    private static Method removeCommandMethod(Object root)
    {
        final Class<?> type = root.getClass();
        if (type == resolvedFor)
            return removeCommand;

        Method found = null;
        for (Class<?> c = type; c != null && found == null; c = c.getSuperclass())
        {
            try
            {
                Method candidate = c.getDeclaredMethod("removeCommand", String.class);
                if (candidate.trySetAccessible())
                    found = candidate;
            }
            catch (NoSuchMethodException ignored)
            {
                // Keep walking up; the method is declared on Paper's root subclass, not Brigadier's
            }
        }

        if (found == null)
        {
            FLog.warning(String.format(
                "%s has no accessible removeCommand(String); TFM commands cannot take labels held by other plugins.",
                type.getName()));
        }

        removeCommand = found;
        resolvedFor = type;
        return found;
    }
}