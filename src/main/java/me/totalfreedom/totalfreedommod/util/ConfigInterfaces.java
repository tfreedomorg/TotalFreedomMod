package me.totalfreedom.totalfreedommod.util;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Custom interfaces for configuration serialization, replacing Aero's interfaces.
 * These interfaces provide a consistent API for objects that can be loaded from and saved to YAML configuration.
 */
public class ConfigInterfaces
{

    /**
     * Interface for objects that can be loaded from a configuration section.
     */
    public interface ConfigLoadable
    {
        /**
         * Loads data from a configuration section.
         *
         * @param cs The configuration section to load from
         */
        void loadFrom(ConfigurationSection cs);
    }

    /**
     * Interface for objects that can validate their own state.
     */
    public interface Validatable
    {
        /**
         * Checks if this object is in a valid state.
         *
         * @return true if the object is valid, false otherwise
         */
        boolean isValid();
    }

}

