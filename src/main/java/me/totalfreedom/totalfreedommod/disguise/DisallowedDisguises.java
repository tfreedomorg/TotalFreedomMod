package me.totalfreedom.totalfreedommod.disguise;

import java.util.*;

import org.bukkit.command.Command;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Manages forbidden disguise types and global disguise state.
 * Based on TF-LibsDisguises functionality.
 */
public class DisallowedDisguises extends FreedomService
{

    // Default forbidden disguise types (from TF-LibsDisguises)
    private static final String[] DEFAULT_FORBIDDEN = new String[]
    {
        "FISHING_HOOK", "ITEM_FRAME", "ENDER_DRAGON", "PLAYER", "GIANT",
        "GHAST", "MAGMA_CUBE", "SLIME", "DROPPED_ITEM", "ENDER_CRYSTAL",
        "AREA_EFFECT_CLOUD", "WITHER"
    };

    /**
     * The LibsDisguises commands that can apply or change a disguise type. Aliases are
     * resolved to these primary names through the command map, so short forms like /d
     * don't need to be listed.
     */
    private static final Set<String> DISGUISE_COMMANDS = Set.of(
        "disguise", "disguiseplayer", "disguiseentity", "disguiseradius", "disguiseclone",
        "disguisemodify", "disguisemodifyplayer", "disguisemodifyentity", "disguisemodifyradius"
    );

    private final Set<String> forbiddenDisguiseTypes = new HashSet<>();
    private boolean disabled = false;

    public DisallowedDisguises(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        loadForbiddenDisguises();
    }

    @Override
    protected void onStop()
    {
        forbiddenDisguiseTypes.clear();
    }

    private void loadForbiddenDisguises()
    {
        forbiddenDisguiseTypes.clear();

        // Load from config
        List<?> configList = ConfigEntry.DISGUISES_FORBIDDEN_TYPES.getList();
        if (configList != null && !configList.isEmpty())
        {
            for (Object item : configList)
            {
                if (item instanceof String)
                {
                    forbiddenDisguiseTypes.add(((String) item).toUpperCase());
                }
            }
        }
        else
        {
            // Use defaults if config is empty
            for (String type : DEFAULT_FORBIDDEN)
            {
                forbiddenDisguiseTypes.add(type);
            }
        }

        FLog.info("Loaded " + forbiddenDisguiseTypes.size() + " forbidden disguise types.");
    }

    /**
     * Checks if a disguise type is allowed.
     * 
     * @param disguiseTypeName The disguise type name (e.g., "ZOMBIE", "CREEPER")
     * @return true if allowed, false if forbidden
     */
    public boolean isAllowed(String disguiseTypeName)
    {
        if (disguiseTypeName == null)
        {
            return false;
        }

        return !forbiddenDisguiseTypes.contains(disguiseTypeName.toUpperCase());
    }

    /**
     * Checks if a disguise type is allowed.
     * 
     * @param entityType The EntityType to check
     * @return true if allowed, false if forbidden
     */
    public boolean isAllowed(EntityType entityType)
    {
        if (entityType == null)
        {
            return false;
        }

        return isAllowed(entityType.name());
    }

    /**
     * Enforces the forbidden type list against LibsDisguises commands.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        final String[] parts = event.getMessage().substring(1).split("\\s+");
        if (parts.length < 2 || !isDisguiseCommand(parts[0]))
            return;

        final String forbidden = Arrays.stream(parts, 1, parts.length)
                                       .filter(this::isForbiddenType)
                                       .findFirst()
                                       .orElse(null);

        if (forbidden == null)
            return;

        event.setCancelled(true);
        FUtil.playerMsg(event.getPlayer(), String.format("The %s disguise is forbidden.", forbidden), NamedTextColor.RED);
        FLog.info(String.format("Blocked forbidden disguise '%s' from %s: %s", 
                forbidden, event.getPlayer().getName(), event.getMessage()));
    }

    /**
     * Whether {@code label} names one of {@link #DISGUISE_COMMANDS}, resolving aliases and any
     * {@code plugin:command} namespace through the command map first.
     */
    private boolean isDisguiseCommand(final String label)
    {
        final int namespace = label.indexOf(':');
        final String bare = (namespace >= 0 ? label.substring(namespace + 1) : label).toLowerCase(Locale.ROOT);
        final Command command = server.getCommandMap().getCommand(bare);
        final String name = command != null ? command.getName().toLowerCase(Locale.ROOT) : bare;

        return DISGUISE_COMMANDS.contains(name);
    }

    /**
     * Matches a command token against the forbidden list, ignoring case and separators.
     */
    private boolean isForbiddenType(final String token)
    {
        final String normalized = normalize(token);

        return forbiddenDisguiseTypes.stream()
                                     .anyMatch(forbidden -> normalize(forbidden).equals(normalized));
    }

    private static String normalize(final String value)
    {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Checks if disguises are globally disabled.
     * 
     * @return true if disabled, false if enabled
     */
    public boolean isDisabled()
    {
        return disabled;
    }

    /**
     * Sets the global disabled state.
     * 
     * @param disabled true to disable all disguises, false to enable
     */
    public void setDisabled(boolean disabled)
    {
        this.disabled = disabled;
    }

    /**
     * Gets the list of forbidden disguise types.
     * 
     * @return A copy of the forbidden disguise types set
     */
    public Set<String> getForbiddenDisguiseTypes()
    {
        return new HashSet<>(forbiddenDisguiseTypes);
    }

    /**
     * Adds a disguise type to the forbidden list.
     * 
     * @param disguiseTypeName The disguise type name to forbid
     */
    public void addForbidden(String disguiseTypeName)
    {
        if (disguiseTypeName != null)
        {
            forbiddenDisguiseTypes.add(disguiseTypeName.toUpperCase());
        }
    }

    /**
     * Removes a disguise type from the forbidden list.
     * 
     * @param disguiseTypeName The disguise type name to allow
     */
    public void removeForbidden(String disguiseTypeName)
    {
        if (disguiseTypeName != null)
        {
            forbiddenDisguiseTypes.remove(disguiseTypeName.toUpperCase());
        }
    }

    /**
     * Reloads the forbidden disguise types from config.
     */
    public void reload()
    {
        loadForbiddenDisguises();
    }
}

