package me.totalfreedom.totalfreedommod.fun;

import java.util.*;

import org.bukkit.GameMode;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.Setter;

public class Jumppads extends FreedomService
{

    public static final double DAMPING_COEFFICIENT = 0.8;
    //
    private final Map<Player, Boolean> pushMap = Maps.newHashMap();
    //
    @Getter
    private JumpPadMode mode;
    @Getter
    @Setter
    private double strength = 0.4;

    public Jumppads(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        String configuredMode = ConfigEntry.JUMPPAD_MODE.getString();
        JumpPadMode foundMode = Arrays.stream(JumpPadMode.values())
                .filter(jumpPadMode -> jumpPadMode.name().equalsIgnoreCase(configuredMode))
                .findFirst()
                .orElse(null);

        if (foundMode == null)
        {
            FLog.warning("Config contains invalid jumppad mode, defaulting to off");
            foundMode = JumpPadMode.OFF;

            ConfigEntry.JUMPPAD_MODE.setString(foundMode.name().toLowerCase());
            plugin.config.save();
        }

        this.mode = foundMode;
    }

    @Override
    public void onStop()
    {

    }

    public void setMode(JumpPadMode mode)
    {
        this.mode = mode;
        ConfigEntry.JUMPPAD_MODE.setString(this.mode.name().toLowerCase());
        plugin.config.save();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (mode == JumpPadMode.OFF || !event.hasExplicitlyChangedBlock())
        {
            return;
        }

        final Player player = event.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR)
        {
            return;
        }

        final Block block = event.getTo().getBlock();
        final Vector velocity = player.getVelocity().clone();

        if (mode == JumpPadMode.NORMAL)
        {
            Boolean canPush = pushMap.get(player);
            if (canPush == null)
            {
                canPush = true;
            }
            if (Tag.WOOL.isTagged(block.getRelative(0, -1, 0).getType()))
            {
                if (canPush)
                {
                    velocity.multiply(strength + 0.85).multiply(-1.0);
                }
                canPush = false;
            }
            else
            {
                canPush = true;
            }
            pushMap.put(player, canPush);
        }
        else
        {
            if (Tag.WOOL.isTagged(block.getRelative(0, -1, 0).getType()))
            {
                velocity.add(new Vector(0.0, strength, 0.0));
            }

            if (mode == JumpPadMode.NORMAL_AND_SIDEWAYS)
            {
                if (Tag.WOOL.isTagged(block.getRelative(1, 0, 0).getType()))
                {
                    velocity.add(new Vector(-DAMPING_COEFFICIENT * strength, 0.0, 0.0));
                }

                if (Tag.WOOL.isTagged(block.getRelative(-1, 0, 0).getType()))
                {
                    velocity.add(new Vector(DAMPING_COEFFICIENT * strength, 0.0, 0.0));
                }

                if (Tag.WOOL.isTagged(block.getRelative(0, 0, 1).getType()))
                {
                    velocity.add(new Vector(0.0, 0.0, -DAMPING_COEFFICIENT * strength));
                }

                if (Tag.WOOL.isTagged(block.getRelative(0, 0, -1).getType()))
                {
                    velocity.add(new Vector(0.0, 0.0, DAMPING_COEFFICIENT * strength));
                }
            }
        }

        if (!player.getVelocity().equals(velocity))
        {
            player.setFallDistance(0.0f);
            player.setVelocity(velocity);
        }
    }

    public enum JumpPadMode
    {
        OFF("Off", "off"),
        NORMAL("Madgeek", "normal", "adhd", "coffee", "on"),
        NORMAL_AND_SIDEWAYS("Normal and Sideways", "both");

        @Getter
        private final String label;
        @Getter
        private final List<String> alternateNames;

        JumpPadMode(String label)
        {
            this.label = label;
            this.alternateNames = Collections.emptyList();
        }

        JumpPadMode(String label, String... alternativeNames)
        {
            this.label = label;
            this.alternateNames = Arrays.stream(alternativeNames).toList();
        }

        public boolean isOn()
        {
            return this != OFF;
        }

        public static JumpPadMode fromString(String input)
        {
            return Arrays.stream(values())
                    .filter(value -> value.getLabel().equalsIgnoreCase(input)
                            || value.getAlternateNames().stream().anyMatch(name -> name.equalsIgnoreCase(input))
                            || value.name().equalsIgnoreCase(input))
                    .findAny()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid mode: " + input));
        }
    }
}
