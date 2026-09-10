package me.totalfreedom.totalfreedommod.disguise;

import me.totalfreedom.api.FreedomAPI;

import java.util.*;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.Command;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

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

    private static final int DEFAULT_MAX_TABLIST_NAME_LENGTH = 32;

    /**
     * The LibsDisguises commands that change a disguise that is already live. These fire
     * no DisguiseEvent, so they are the only ones still worth screening from the command line.
     * Aliases resolve to these primary names through the command map.
     */
    private static final Set<String> MODIFY_COMMANDS = Set.of(
        "disguisemodify", "disguisemodifyplayer", "disguisemodifyentity", "disguisemodifyradius"
    );

    private final Set<String> forbiddenDisguiseTypes = new HashSet<>();
    private final Set<String> forbiddenOptions = new HashSet<>();
    private boolean disabled = false;
    private int maxTablistNameLength = DEFAULT_MAX_TABLIST_NAME_LENGTH;

    public DisallowedDisguises(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        loadForbiddenDisguises();
    }

    @Override
    public void onStop()
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

        final Integer configuredMax = ConfigEntry.DISGUISES_MAX_TABLIST_NAME_LENGTH.getInteger();
        maxTablistNameLength = configuredMax != null && configuredMax > 0
            ? configuredMax
            : DEFAULT_MAX_TABLIST_NAME_LENGTH;

        forbiddenOptions.clear();
        final List<?> optionList = ConfigEntry.DISGUISES_FORBIDDEN_OPTIONS.getList();
        if (optionList != null)
        {
            optionList.stream()
                      .filter(String.class::isInstance)
                      .map(String.class::cast)
                      .forEach(forbiddenOptions::add);
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

    /**
     * The LibsDisguises watcher getters that must not report true on an applied disguise.
     */
    public Set<String> getForbiddenOptions()
    {
        return Set.copyOf(forbiddenOptions);
    }

    /**
     * Judges a disguise that is about to be applied.
     * 
     * @param typeName       the resolved disguise type, or null when it could not be read
     * @param setOptions     the forbidden options found set on this disguise
     * @param tablistName    the injected tab list name, or null when there is none
     * @return the refusal reason, or empty to allow
     */
    public Optional<String> denyReason(final String typeName, final Set<String> setOptions, final String tablistName)
    {
        if (disabled)
            return Optional.of("Disguises are currently disabled.");

        if (typeName != null && !isAllowed(typeName))
            return Optional.of(String.format("The %s disguise is forbidden.", typeName));

        if (!setOptions.isEmpty())
            return Optional.of(String.format(
                "That disguise uses a forbidden option: %s.", String.join(", ", setOptions)));

        if (tablistName != null && tablistName.length() > maxTablistNameLength)
            return Optional.of(String.format(
                "That disguise's tab list name is too long (max %d characters).", maxTablistNameLength));

        return Optional.empty();
    }

    /**
     * Cheap first layer for the modify family only. Applying a disguise is judged at apply time in
     * LibsDisguiseBridge, which reads the resolved disguise and so cannot be fooled by a saved one.
     * However, Disguise#startDisguise is the only place LibsDisguises fires DisguiseEvent, so
     * modifying a disguise that is already live fires nothing and the command line is all we have.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        final String[] parts = event.getMessage().substring(1).split("\\s+");
        if (parts.length < 2 || !isModifyCommand(parts[0]))
            return;

        if (plugin.admins().isAdmin(event.getPlayer()))
            return;

        final String forbidden = Arrays.stream(parts, 1, parts.length)
                                     .filter(token -> isForbiddenType(token) || isForbiddenOption(token))
                                     .findFirst()
                                     .orElse(null);

        if (forbidden == null)
            return;

        event.setCancelled(true);
        FUtil.playerMsg(event.getPlayer(), String.format("'%s' may not be used on a disguise.", forbidden), NamedTextColor.RED);
        FLog.info(String.format("Blocked disguise modification '%s' from %s: %s",
                forbidden, event.getPlayer().getName(), event.getMessage()));
    }

    /**
     * Matches a token against the forbidden option list. Option names are LibsDisguises setters
     * ("setDisplayedInTab") while the configured entries are its getters ("isDisplayedInTab"), so
     * both are compared on their stem.
     */
    private boolean isForbiddenOption(final String token)
    {
        final String stem = stemOption(token);

        return forbiddenOptions.stream()
                               .anyMatch(option -> stemOption(option).equals(stem));
    }

    private static String stemOption(final String value)
    {
        final String upper = value.toUpperCase(Locale.ROOT);
        final String stripped = upper.startsWith("SET") ? upper.substring(3)
            : upper.startsWith("IS") ? upper.substring(2)
            : upper;

        return stripped.replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Whether {@code label} names one of {@link #MODIFY_COMMANDS}, resolving aliases and any
     * {@code plugin:command} namespace through the command map first.
     */
    private boolean isModifyCommand(final String label)
    {
        final int namespace = label.indexOf(':');
        final String bare = (namespace >= 0 ? label.substring(namespace + 1) : label).toLowerCase(Locale.ROOT);
        final Command command = server.getCommandMap().getCommand(bare);
        final String name = command != null ? command.getName().toLowerCase(Locale.ROOT) : bare;

        return MODIFY_COMMANDS.contains(name);
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

