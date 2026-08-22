package me.totalfreedom.totalfreedommod.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import me.totalfreedom.totalfreedommod.PluginProvider;

public enum ConfigEntry
{

    FORCE_IP_ENABLED(Boolean.class, "forceip.enabled"),
    FORCE_IP_PORT(Integer.class, "forceip.port"),
    FORCE_IP_KICKMSG(String.class, "forceip.kickmsg"),
    //
    ALLOW_EXPLOSIONS(Boolean.class, "allow.explosions"),
    ALLOW_FIRE_PLACE(Boolean.class, "allow.fire_place"),
    ALLOW_FIRE_SPREAD(Boolean.class, "allow.fire_spread"),
    ALLOW_FLUID_SPREAD(Boolean.class, "allow.fluid_spread"),
    ALLOW_LAVA_DAMAGE(Boolean.class, "allow.lava_damage"),
    ALLOW_LAVA_PLACE(Boolean.class, "allow.lava_place"),
    ALLOW_TNT_MINECARTS(Boolean.class, "allow.tnt_minecarts"),
    ALLOW_WATER_PLACE(Boolean.class, "allow.water_place"),
    ALLOW_REDSTONE(Boolean.class, "allow.redstone"),
    ALLOW_SIGN_PLACE(Boolean.class, "allow.sign_place"),
    ALLOW_FALLING_SIGNS(Boolean.class, "allow.falling_signs"),
    ALLOW_FALLING_BLOCKS(Boolean.class, "allow.falling_blocks"),
    AUTO_TP(Boolean.class, "allow.auto_tp"),
    AUTO_CLEAR(Boolean.class, "allow.auto_clear"),
    //
    MOB_LIMITER_ENABLED(Boolean.class, "moblimiter.enabled"),
    MOB_LIMITER_MAX(Integer.class, "moblimiter.max"),
    MOB_LIMITER_DISABLED_MOBS(List.class, "moblimiter.disabled"),
    //
    HTTPD_ENABLED(Boolean.class, "httpd.enabled"),
    HTTPD_PORT(Integer.class, "httpd.port"),
    HTTPD_PUBLIC_FOLDER(String.class, "httpd.public_folder"),
    HTTPD_SCHEM_FOLDER(String.class, "httpd.schem_folder"),
    HTTPD_TRUSTED_PROXIES(List.class, "httpd.trusted_proxies"),
    //
    SSH_ENABLED(Boolean.class, "ssh.enabled"),
    SSH_PORT(Integer.class, "ssh.port"),
    SSH_AUTH_PASSWORD(Boolean.class, "ssh.auth.password"),
    SSH_AUTH_KEY(Boolean.class, "ssh.auth.key"),
    SSH_AUTH_TOTP(Boolean.class, "ssh.auth.totp"),
    SSH_PASSWORD(String.class, "ssh.password"),
    SSH_MAX_FAILED_ATTEMPTS(Integer.class, "ssh.max_failed_attempts"),
    SSH_SHOW_USER(Boolean.class, "ssh.show_user"),
    SSH_USER_PREFIX(String.class, "ssh.user_prefix"),
    SSH_INHERIT_RANK(Boolean.class, "ssh.inherit_rank"),
    SSH_QR_PORT(Integer.class, "ssh.qr_port"),
    //
    DISCORD_ENABLED(Boolean.class, "discord.enabled"),
    DISCORD_TOKEN(String.class, "discord.token"),
    DISCORD_GUILD_ID(String.class, "discord.guild_id"),
    DISCORD_PUBLIC_CHANNEL_ID(String.class, "discord.public_channel_id"),
    DISCORD_ADMINCHAT_CHANNEL_ID(String.class, "discord.adminchat_channel_id"),
    DISCORD_CONSOLE_CHANNEL_ID(String.class, "discord.console_channel_id"),
    DISCORD_CHAT_FORMAT(String.class, "discord.chat_format"),
    DISCORD_CHANNEL_FORMAT(String.class, "discord.channel_format"),
    DISCORD_ADMINCHAT_FORMAT(String.class, "discord.adminchat_format"),
    DISCORD_ADMINCHAT_CHANNEL_FORMAT(String.class, "discord.adminchat_channel_format"),
    DISCORD_CONSOLE_FLUSH(Integer.class, "discord.console_flush"),
    DISCORD_CONSOLE_QUEUE_LIMIT(Integer.class, "discord.console_queue_limit"),
    DISCORD_LINK_CODE_TTL(Integer.class, "discord.link_code_ttl"),
    DISCORD_RECONNECT_INTERVAL_SECONDS(Integer.class, "discord.reconnect.interval_seconds"),
    DISCORD_RECONNECT_MAX_ATTEMPTS(Integer.class, "discord.reconnect.max_attempts"),
    DISCORD_SERVER_STARTUP_MESSAGE(String.class, "discord.messages.server_startup"),
    DISCORD_SERVER_SHUTDOWN_MESSAGE(String.class, "discord.messages.server_shutdown"),
    DISCORD_PLAYER_JOIN_MESSAGE(String.class, "discord.messages.player_join"),
    DISCORD_PLAYER_LEAVE_MESSAGE(String.class, "discord.messages.player_leave"),
    DISCORD_PLUGIN_RELOAD_MESSAGE(String.class, "discord.messages.reload"),
    DISCORD_PLAYER_BAN_MESSAGE(String.class, "discord.messages.ban"),
    DISCORD_PLAYER_KICK_MESSAGE(String.class, "discord.messages.kick"),
    DISCORD_PLAYER_TBAN_MESSAGE(String.class, "discord.messages.tban"),
    DISCORD_PLAYER_WARN_MESSAGE(String.class, "discord.messages.warn"),
    DISCORD_PLAYER_SMITE_MESSAGE(String.class, "discord.messages.smite"),
    DISCORD_SAY_MESSAGE(String.class, "discord.messages.say"),
    //
    SERVER_COLORFUL_MOTD(Boolean.class, "server.colorful_motd"),
    SERVER_NAME(String.class, "server.name"),
    SERVER_ADDRESS(String.class, "server.address"),
    SERVER_MOTD(String.class, "server.motd"),
    SERVER_OWNERS(List.class, "server.owners"),
    SERVER_WEB_URL(String.class, "server.web_url"),
    SERVER_BAN_URL(String.class, "server.ban_url"),
    SERVER_PERMBAN_URL(String.class, "server.permban_url"),
    SERVER_PLAYER_JOIN_MESSAGE(String.class, "server.messages.player_join"),
    SERVER_PLAYER_LEAVE_MESSAGE(String.class, "server.messages.player_leave"),
    //
    ADMINLIST_CLEAN_THESHOLD_HOURS(Integer.class, "adminlist.clean_threshold_hours"),
    ADMINLIST_MOJANG_UUID_LOOKUP(Boolean.class, "adminlist.mojang_uuid_lookup"),
    ADMINLIST_USE_UUID_ONLY(Boolean.class, "adminlist.use_uuid_only"),
    //
    DISABLE_NIGHT(Boolean.class, "disable.night"),
    DISABLE_WEATHER(Boolean.class, "disable.weather"),
    DISABLE_SPAWNERS(Boolean.class, "disable.spawners"),
    DISABLE_SPAWNER_PLACE(Boolean.class, "disable.spawner_place"),
    DISABLE_PORTAL_CREATE(Boolean.class, "disable.portal_create"),
    DISABLE_PISTONS(Boolean.class, "disable.pistons"),
    DISABLE_ENTITY_SPAM(Boolean.class, "disable.entity_spam"),
    DISABLE_ENTITY_SPAM_MAX(Integer.class, "disable.entity_spam_max"),
    //
    ENABLE_PET_PROTECT(Boolean.class, "petprotect.enabled"),
    BLOCK_SPECTATOR_TELEPORT(Boolean.class, "block_spectator_teleport"),
    //
    LANDMINES_ENABLED(Boolean.class, "landmines_enabled"),
    TOSSMOB_ENABLED(Boolean.class, "tossmob_enabled"),
    MP44_ENABLED(Boolean.class, "mp44_enabled"),
    JUMPPAD_MODE(String.class, "jumppad.mode"),
    //
    PROTECTAREA_ENABLED(Boolean.class, "protectarea.enabled"),
    PROTECTAREA_SPAWNPOINTS(Boolean.class, "protectarea.auto_protect_spawnpoints"),
    PROTECTAREA_RADIUS(Double.class, "protectarea.auto_protect_radius"),
    PROTECTAREA_PROTECT_PLAYERS(Boolean.class, "protectarea.protect_players"),
    PROTECTAREA_BLOCK_POTIONS(Boolean.class, "protectarea.block_potions"),
    PROTECTAREA_BLOCK_ITEMS(Boolean.class, "protectarea.block_items"),
    //
    WORLDEDIT_ENABLED(Boolean.class, "worldedit.enabled"),
    WORLDEDIT_LIMIT_MAX(Integer.class, "worldedit.limit_max"),
    WORLDEDIT_DEOP_ON_LIMIT_ABUSE(Boolean.class, "worldedit.deop_on_limit_abuse"),
    WORLDEDIT_MAX_SELECTION_VOLUME(Integer.class, "worldedit.max_selection_volume"),
    WORLDEDIT_RADIUS_MAX(Integer.class, "worldedit.radius_max"),
    WORLDEDIT_MAX_PATTERN_BLOCKS(Integer.class, "worldedit.max_pattern_blocks"),
    WORLDEDIT_BLOCKED_BLOCK_TYPES(List.class, "worldedit.blocked_types"),
    WORLDEDIT_BLOCK_CATEGORY_TAGS(Boolean.class, "worldedit.block_category_tags"),
    WORLDEDIT_BLOCK_CLIPBOARD_PATTERN(Boolean.class, "worldedit.block_clipboard_pattern"),
    WORLDEDIT_THROTTLE_ENABLED(Boolean.class, "worldedit.throttle.enabled"),
    WORLDEDIT_THROTTLE_MAX_OPS(Integer.class, "worldedit.throttle.max_operations"),
    WORLDEDIT_THROTTLE_TIME_WINDOW(Integer.class, "worldedit.throttle.time_window"),
    WORLDEDIT_THROTTLE_MAX_CANCELLED_OPS(Integer.class, "worldedit.throttle.max_cancelled_operations"),
    WORLDEDIT_MAX_CONTAINERS(Integer.class, "worldedit.max_containers"),
    WORLDEDIT_MAX_BLOCK_ENTITIES_PER_OP(Integer.class, "worldedit.max_block_entities_per_op"),
    WORLDEDIT_MAX_SCHEM_SAVE_KB(Integer.class, "worldedit.max_schem_save_kb"),
    WORLDEDIT_SCHEM_SAVE_COOLDOWN(Integer.class, "worldedit.schem_save_cooldown_seconds"),
    //
    NUKE_MONITOR_ENABLED(Boolean.class, "nukemonitor.enabled"),
    NUKE_MONITOR_COUNT_BREAK(Integer.class, "nukemonitor.count_break"),
    NUKE_MONITOR_COUNT_PLACE(Integer.class, "nukemonitor.count_place"),
    NUKE_MONITOR_RANGE(Double.class, "nukemonitor.range"),
    //
    ANTIDROP_ENABLED(Boolean.class, "antidrop.enabled"),
    ANTIDROP_TIME_WINDOW(Integer.class, "antidrop.time_window"),
    ANTIDROP_DROP_LIMIT(Integer.class, "antidrop.drop_limit"),
    ANTIDROP_DROP_EJECT_LIMIT(Integer.class, "antidrop.drop_eject_limit"),
    ANTIDROP_DROP_ITEM_LIMIT(Integer.class, "antidrop.drop_item_limit"),
    ANTIDROP_DROP_ITEM_EJECT_LIMIT(Integer.class, "antidrop.drop_item_eject_limit"),
    //
    ANTISPAM_ENABLED(Boolean.class, "antispam.enabled"),
    ANTISPAM_LIMIT(Integer.class, "antispam.limit"),
    ANTISPAM_TIME_WINDOW(Integer.class, "antispam.time_window"),
    ANTISPAM_CHAT_COOLDOWN(Integer.class, "antispam.chat_cooldown"),
    ANTISPAM_REPEAT_WINDOW(Integer.class, "antispam.repeat_window"),
    ANTISPAM_REPEAT_HISTORY(Integer.class, "antispam.repeat_history"),
    //
    TEXT_FILTER_ENABLED(Boolean.class, "text_filter.enabled"),
    TEXT_FILTER_REGEX_FILTERS(List.class, "text_filter.regex_filters"),
    //
    MOVE_GUARD_ENABLED(Boolean.class, "move_guard.enabled"),
    MOVE_GUARD_SPEED_MAX_HORIZONTAL_DELTA(Integer.class, "move_guard.speed.max_horizontal_delta"),
    MOVE_GUARD_SPEED_MAX_TELEPORTS_PER_SECOND(Integer.class, "move_guard.speed.max_teleports_per_second"),
    MOVE_GUARD_SPEED_MAX_BLOCKS_PER_SECOND(Integer.class, "move_guard.speed.max_blocks_per_second"),
    //
    CRASH_ITEMS_PREVENT(Boolean.class, "crash_items.prevent"),
    CRASH_ITEMS_PANIC_MODE(Boolean.class, "crash_items.panic_mode"),
    CRASH_ITEMS_PACKET_GUARD(Boolean.class, "crash_items.packet_guard"),
    CRASH_ITEMS_PACKET_RATE_LIMIT(Boolean.class, "crash_items.packet_rate_limit"),
    CRASH_ITEMS_MAX_INTERACTIONS_PER_SECOND(Integer.class, "crash_items.max_interactions_per_second"),
    CRASH_ITEMS_MAX_COMMANDS_PER_SECOND(Integer.class, "crash_items.max_commands_per_second"),
    CRASH_ITEMS_MAX_MOVEMENT_PER_SECOND(Integer.class, "crash_items.max_movement_packets_per_second"),
    CRASH_ITEMS_MAX_HOTBAR_SLOTS_PER_SECOND(Integer.class, "crash_items.max_hotbar_slots_per_second"),
    CRASH_ITEMS_MAX_COMMAND_SUGGESTIONS_PER_SECOND(Integer.class, "crash_items.max_command_suggestions_per_second"),
    CRASH_ITEMS_MAX_GAMEMODE_SWITCHES_PER_SECOND(Integer.class, "crash_items.max_gamemode_switches_per_second"),
    CRASH_ITEMS_EQUIPMENT_SWEEP_TICKS(Integer.class, "crash_items.equipment_sweep_ticks"),
    CRASH_ITEMS_BASE_COMMANDS(List.class, "crash_items.base_commands"),
    CRASH_ITEMS_HIDE_CONSOLE_SPAM(Boolean.class, "crash_items.hide_console_spam"),
    CRASH_ITEMS_MAX_POTION_EFFECTS(Integer.class, "crash_items.max_potion_effects"),
    CRASH_ITEMS_COMMAND_DEPTH_GUARD(Boolean.class, "crash_items.command_depth_guard"),
    CRASH_ITEMS_MAX_COMMAND_DEPTH(Integer.class, "crash_items.max_command_depth"),
    //
    CRASH_CONTAINERS_SCAN_CHUNK_LOAD(Boolean.class, "crash_containers.scan_chunk_load"),
    CRASH_CONTAINERS_SWEEP_MODE(String.class, "crash_containers.sweep_mode"),
    CRASH_CONTAINERS_SWEEP_TICKS(Integer.class, "crash_containers.sweep_ticks"),
    CRASH_CONTAINERS_SWEEP_RADIUS(Integer.class, "crash_containers.sweep_radius"),
    CRASH_CONTAINERS_PACKET_GUARD(Boolean.class, "crash_containers.packet_guard"),
    CRASH_CONTAINERS_CHUNK_GUARD(Boolean.class, "crash_containers.chunk_guard"),
    //
    CRASH_SPAWNERS_PREVENT(Boolean.class, "crash_spawners.prevent"),
    CRASH_SPAWNERS_PACKET_GUARD(Boolean.class, "crash_spawners.packet_guard"),
    CRASH_SPAWNERS_CHUNK_GUARD(Boolean.class, "crash_spawners.chunk_guard"),
    //
    CRASH_SIGNS_PREVENT(Boolean.class, "crash_signs.prevent"),
    CRASH_SIGNS_SCAN_CHUNK_LOAD(Boolean.class, "crash_signs.scan_chunk_load"),
    CRASH_SIGNS_PACKET_GUARD(Boolean.class, "crash_signs.packet_guard"),
    CRASH_SIGNS_CHUNK_GUARD(Boolean.class, "crash_signs.chunk_guard"),
    CRASH_SIGNS_SWEEP_TICKS(Integer.class, "crash_signs.sweep_ticks"),
    CRASH_SIGNS_SWEEP_RADIUS(Integer.class, "crash_signs.sweep_radius"),
    //
    CRASH_ENTITIES_PREVENT(Boolean.class, "crash_entities.prevent"),
    CRASH_ENTITIES_MAX_NAME_LENGTH(Integer.class, "crash_entities.max_name_length"),
    CRASH_ENTITIES_MAX_COMPONENT_NODES(Integer.class, "crash_entities.max_component_nodes"),
    CRASH_ENTITIES_SWEEP_TICKS(Integer.class, "crash_entities.sweep_ticks"),
    CRASH_ENTITIES_SCREEN_COMMANDS(Boolean.class, "crash_entities.screen_commands"),
    CRASH_ENTITIES_STRIP_NAME_VISIBLE(Boolean.class, "crash_entities.strip_name_visible"),
    CRASH_ENTITIES_MAX_SCALE(Double.class, "crash_entities.max_scale"),
    CRASH_ENTITIES_MAX_SLIME_SIZE(Integer.class, "crash_entities.max_slime_size"),
    CRASH_ENTITIES_MAX_PAINTING_BLOCKS(Integer.class, "crash_entities.max_painting_size"),
    CRASH_ENTITIES_SCALE_SWEEP_TICKS(Integer.class, "crash_entities.scale_sweep_ticks"),
    CRASH_ENTITIES_NEUTRALIZE_WAYPOINTS(Boolean.class, "crash_entities.neutralize_entity_waypoints"),
    CRASH_ENTITIES_WAYPOINT_SWEEP_TICKS(Integer.class, "crash_entities.waypoint_sweep_ticks"),
    //
    WORLD_BORDER(Integer.class, "world_border"),
    //
    AUTOEJECT_ENABLED(Boolean.class, "autoeject.enabled"),
    AUTOEJECT_PERSIST(Boolean.class, "autoeject.persist"),
    AUTOEJECT_TIME_WINDOW(Integer.class, "autoeject.time_window"),
    AUTOEJECT_STRIKE_ONE_TIMEOUT(Integer.class, "autoeject.strike_one.timeout"),
    AUTOEJECT_STRIKE_TWO_TIMEOUT(Integer.class, "autoeject.strike_two.timeout"),
    AUTOEJECT_STRIKE_THREE_TIMEOUT(Integer.class, "autoeject.strike_three.timeout"),
    //
    AUTO_OP_ENABLED(Boolean.class, "auto_op.enabled"),
    AUTO_OP_PERSISTENT_MONITOR(Boolean.class, "auto_op.persistent_monitor"),
    AUTO_OP_MONITOR_INTERVAL(Integer.class, "auto_op.monitor_interval"),
    //
    SPAWN_WORLD(String.class, "spawn.world"),
    SPAWN_X(Double.class, "spawn.x"),
    SPAWN_Y(Double.class, "spawn.y"),
    SPAWN_Z(Double.class, "spawn.z"),
    SPAWN_YAW(Double.class, "spawn.yaw"),
    SPAWN_PITCH(Double.class, "spawn.pitch"),
    SPAWN_SEND_ON_JOIN(String.class, "spawn.send_player.on_join"),
    SPAWN_SEND_ON_RESPAWN(Boolean.class, "spawn.send_player.on_respawn"),
    //
    FLATLANDS_GENERATE(Boolean.class, "flatlands.generate"),
    FLATLANDS_GENERATE_PARAMS(String.class, "flatlands.generate_params"),
    //
    ANNOUNCER_ENABLED(Boolean.class, "announcer.enabled"),
    ANNOUNCER_INTERVAL(Integer.class, "announcer.interval"),
    ANNOUNCER_PREFIX(String.class, "announcer.prefix"),
    ANNOUNCER_ANNOUNCEMENTS(List.class, "announcer.announcements"),
    //
    EXPLOSIVE_RADIUS(Double.class, "explosive_radius"),
    FREECAM_TRIGGER_COUNT(Integer.class, "freecam_trigger_count"),
    BLOCKED_COMMANDS(List.class, "blocked_commands"),
    BLOCK_SERVER_COMMANDS_ENABLED(Boolean.class, "server_command_blocker.enabled"),
    BLOCK_SERVER_COMMANDS_BLOCK_AT_NAMED_SENDERS(Boolean.class, "server_command_blocker.block_at_named_senders"),
    BLOCK_SERVER_COMMANDS_BLOCK_COMMAND_BLOCK_HOLDERS(Boolean.class, "server_command_blocker.block_command_block_holders"),
    BLOCK_SERVER_COMMANDS_BLOCKED_SUBSTRINGS(List.class, "server_command_blocker.blocked_substrings"),
    BLOCK_SERVER_COMMANDS_LOG_THROTTLED_WARNINGS(Boolean.class, "server_command_blocker.log_throttled_warnings"),
    BLOCK_SERVER_COMMANDS_LOG_INTERVAL_TICKS(Integer.class, "server_command_blocker.log_interval_ticks"),
    HOST_SENDERS(List.class, "host_senders"),
    FAMOUS_PLAYERS(List.class, "famous_players"),
    NOADMIN_IPS(List.class, "noadmin_ips"),
    MASK_IPS(Boolean.class, "mask_ips"),
    RANGE_BAN_IPS(Boolean.class, "range_ban_ips"),
    LOCKDOWN_MODE(Boolean.class, "lockdown_mode"),
    ADMIN_ONLY_MODE(Boolean.class, "admin_only_mode"),
    ADMIN_INFO(List.class, "admininfo"),
    AUTO_ENTITY_WIPE(Boolean.class, "auto_wipe"),
    AUTO_ENTITY_WIPE_INTERVAL(Integer.class, "auto_wipe_interval"),
    DISGUISES_FORBIDDEN_TYPES(List.class, "disguises.forbidden_types"),
    //
    VAULT_CHAT_PROVIDER_ENABLED(Boolean.class, "chat.provider.enabled"),
    VAULT_CHAT_PROVIDER_OVERRIDE_EXISTING(Boolean.class, "chat.provider.override_existing"),
    VAULT_CHAT_FORMAT(String.class, "chat.format.message"),
    VAULT_CHAT_TAG(String.class, "chat.format.tag"),
    VAULT_CHAT_ENFORCE_PREFIX(Boolean.class, "chat.behavior.enforce_prefix"),
    VAULT_CHAT_NO_CAPS(Boolean.class, "chat.behavior.no_caps.enabled"),
    VAULT_CHAT_CAPS_RATIO(Integer.class, "chat.behavior.no_caps.caps_ratio"),
    VAULT_CHAT_PREVENT_SPAM(Boolean.class, "chat.behavior.prevent_spam"),
    VAULT_CHAT_ALLOW_COLOR_FORMATTING(Boolean.class, "chat.behavior.allow_color_formatting"),
    VAULT_CHAT_ALLOW_SPECIAL_FORMATTING(Boolean.class, "chat.behavior.allow_special_formatting"),
    VAULT_CHAT_MAX_MESSAGE_LENGTH(Integer.class, "chat.behavior.max_message_length"),
    VAULT_CHAT_NOTIFY_TRUNCATED(Boolean.class, "chat.behavior.notify_truncated"),
    VAULT_PREFIX_IMPOSTOR(String.class, "chat.prefix.impostor"),
    VAULT_PREFIX_NON_OP(String.class, "chat.prefix.non_op"),
    VAULT_PREFIX_OP(String.class, "chat.prefix.op"),
    VAULT_PREFIX_SUPER_ADMIN(String.class, "chat.prefix.super_admin"),
    VAULT_PREFIX_SENIOR_ADMIN(String.class, "chat.prefix.senior_admin"),
    VAULT_PREFIX_SENIOR_CONSOLE(String.class, "chat.prefix.senior_console"),
    VAULT_PREFIX_DEVELOPER(String.class, "chat.prefix.developer"),
    VAULT_PREFIX_OWNER(String.class, "chat.prefix.owner"),
    //
    TABLIST_ENABLED(Boolean.class, "tablist.enabled"),
    TABLIST_COLORFUL(Boolean.class, "tablist.colorful_tablist"),
    TABLIST_CHANGE_COLORS(Integer.class, "tablist.change_colors"),
    TABLIST_HEADER(String.class, "tablist.header"),
    TABLIST_FOOTER(String.class, "tablist.footer"),
    TABLIST_TYPE(String.class, "tablist.type"),
    TABLIST_SIZE(Integer.class, "tablist.size"),
    TABLIST_COLUMNS(Integer.class, "tablist.columns"),
    TABLIST_PLAYER_COMPONENT(String.class, "tablist.player_component"),
    TABLIST_AFK_TAG(String.class, "tablist.afk_tag"),
    TABLIST_UPDATE_INTERVAL(Integer.class, "tablist.update_interval"),
    //
    GAMERULES_ENABLED(Boolean.class, "gamerules.enabled"),
    GAMERULES_ENFORCEMENT_DELAY(Integer.class, "gamerules.enforcement_interval"),
    GAMERULES_DEFAULTS(ConfigurationSection.class, "gamerules.defaults"),
    GAMERULES_MALICIOUS(List.class, "gamerules.malicious"),
    GAMERULES_CAPS(ConfigurationSection.class, "gamerules.caps"),
    GAMERULES_MAX_CHANGES_PER_SECOND(Integer.class, "gamerules.max_changes_per_second"),
    GAMERULES_KICK_FLOODERS(Boolean.class, "gamerules.kick_flooders"),
    //
    GAMEMODE_FLOOD_ENABLED(Boolean.class, "gamemode_flood.enabled"),
    GAMEMODE_FLOOD_MAX_CHANGES_PER_SECOND(Integer.class, "gamemode_flood.max_changes_per_second"),
    GAMEMODE_FLOOD_KICK_FLOODERS(Boolean.class, "gamemode_flood.kick_flooders");

    //
    private final Class<?> type;
    private final String configName;

    private ConfigEntry(Class<?> type, String configName)
    {
        this.type = type;
        this.configName = configName;
    }

    public Class<?> getType()
    {
        return type;
    }

    public String getConfigName()
    {
        return configName;
    }

    public String getString()
    {
        return getConfig().getString(this);
    }

    public String setString(String value)
    {
        getConfig().setString(this, value);
        return value;
    }

    public Double getDouble()
    {
        return getConfig().getDouble(this);
    }

    public Double setDouble(Double value)
    {
        getConfig().setDouble(this, value);
        return value;
    }

    public Boolean getBoolean()
    {
        return getConfig().getBoolean(this);
    }

    public boolean getBoolean(boolean defaultValue)
    {
        final Boolean v = getBoolean();
        return v == null ? defaultValue : v;
    }

    public Boolean setBoolean(Boolean value)
    {
        getConfig().setBoolean(this, value);
        return value;
    }

    public Integer getInteger()
    {
        return getConfig().getInteger(this);
    }

    public int getInteger(int defaultValue)
    {
        final Integer v = getInteger();
        return v == null ? defaultValue : v;
    }

    public Integer setInteger(Integer value)
    {
        getConfig().setInteger(this, value);
        return value;
    }

    public List<?> getList()
    {
        List<?> value = getConfig().getList(this);
        return value != null ? value : Collections.emptyList();
    }

    public List<String> getStringList()
    {
        final List<?> raw = getList();
        final List<String> out = new ArrayList<>(raw.size());
        for (Object element : raw)
        {
            if (element != null)
            {
                out.add(String.valueOf(element));
            }
        }
        return out;
    }

    public ConfigurationSection getSection()
    {
        return getConfig().get(this, ConfigurationSection.class);
    }

    private MainConfig getConfig()
    {
        return PluginProvider.get().config;
    }

    /** Effective max component-graph nodes for cursed-text scanning (default 1024). */
    public static int maxComponentNodes()
    {
        return CRASH_ENTITIES_MAX_COMPONENT_NODES.getInteger(1024);
    }

    public static ConfigEntry findConfigEntry(String name)
    {
        name = name.toLowerCase().replace("_", "");
        for (ConfigEntry entry : values())
        {
            if (entry.toString().toLowerCase().replace("_", "").equals(name))
            {
                return entry;
            }
        }
        return null;
    }
}
