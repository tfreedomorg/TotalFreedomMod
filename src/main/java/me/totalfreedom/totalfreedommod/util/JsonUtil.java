package me.totalfreedom.totalfreedommod.util;

import java.util.Date;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.rank.RankRole;

import com.google.gson.*;

/**
 * Shared Gson instance for the plugin's JSON-backed persistence (SQL write-through
 * snapshots, bundled resource files). Registers adapters for the types Gson can't
 * serialize sensibly on its own via reflection.
 */
public final class JsonUtil
{
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(UUID.class, (JsonSerializer<UUID>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
            .registerTypeAdapter(UUID.class, (JsonDeserializer<UUID>) (json, type, ctx) -> UUID.fromString(json.getAsString()))
            .registerTypeAdapter(NamedTextColor.class, (JsonSerializer<NamedTextColor>) (src, type, ctx) ->
                    new JsonPrimitive(NamedTextColor.NAMES.keyOrThrow(src)))
            .registerTypeAdapter(NamedTextColor.class, (JsonDeserializer<NamedTextColor>) (json, type, ctx) ->
            {
                NamedTextColor color = NamedTextColor.NAMES.value(json.getAsString().toLowerCase());
                return color != null ? color : NamedTextColor.WHITE;
            })
            .registerTypeAdapter(Date.class, (JsonSerializer<Date>) (src, type, ctx) -> new JsonPrimitive(FUtil.dateToString(src)))
            .registerTypeAdapter(Date.class, (JsonDeserializer<Date>) (json, type, ctx) -> FUtil.stringToDate(json.getAsString()))
            .registerTypeAdapter(Component.class, (JsonSerializer<Component>) (src, type, ctx) ->
                    new JsonPrimitive(AdventureUtil.componentToLegacy(src)))
            .registerTypeAdapter(Component.class, (JsonDeserializer<Component>) (json, type, ctx) ->
                    AdventureUtil.legacyToComponent(json.getAsString()))
            .registerTypeAdapter(RankRole.class, (JsonSerializer<RankRole>) (src, type, ctx) ->
                    new JsonPrimitive(src.getId()))
            .registerTypeAdapter(RankRole.class, (JsonDeserializer<RankRole>) (json, type, ctx) ->
                    RankRole.fromId(json.getAsString()).orElse(null))
            .create();

    private JsonUtil()
    {
    }
}
