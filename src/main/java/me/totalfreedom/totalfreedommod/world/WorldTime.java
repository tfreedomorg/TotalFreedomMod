package me.totalfreedom.totalfreedommod.world;

import java.util.Arrays;
import java.util.List;

import org.bukkit.World;

public enum WorldTime
{

    INHERIT(),
    SUNRISE("sunrise,morning", 0),
    NOON("noon,midday,day", 6000),
    SUNSET("sunset,evening", 12000),
    MIDNIGHT("midnight,night", 18000);
    //
    private final int timeTicks;
    private final List<String> aliases;

    private WorldTime()
    {
        this.timeTicks = 0;
        this.aliases = null;
    }

    private WorldTime(String aliases, int timeTicks)
    {
        this.timeTicks = timeTicks;
        this.aliases = Arrays.asList(aliases.split(","));
    }

    public int getTimeTicks()
    {
        return timeTicks;
    }

    /**
     * The spellings {@link #getByAlias} accepts for this constant. Empty for {@link #INHERIT},
     * which by design cannot be named on the command line.
     *
     * @see me.totalfreedom.totalfreedommod.cmd.resolver.WorldTimeArgumentResolver
     */
    public List<String> getAliases()
    {
        return aliases != null ? aliases : List.of();
    }

    public void setWorldToTime(World world)
    {
        world.setTime(getTimeTicks());
    }

    public static WorldTime getByAlias(String needle)
    {
        needle = needle.toLowerCase();
        for (WorldTime time : values())
        {
            if (time.aliases != null && time.aliases.contains(needle))
            {
                return time;
            }
        }
        return null;
    }
}
