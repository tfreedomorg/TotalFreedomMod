package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.Arrays;
import java.util.List;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.world.WorldWeather;

public class WeatherArgumentResolver implements AbstractArgumentResolver<WorldWeather> 
{
    @Override
    public String name()
    {
        return "WorldWeather";
    }

    /**
     * Aliases rather than constant names: {@link WorldWeather#getByAlias} only matches aliases.
     */
    @Override
    public List<String> suggestions()
    {
        return Arrays.stream(WorldWeather.values())
                     .map(WorldWeather::getAliases)
                     .flatMap(List::stream)
                     .sorted()
                     .toList();
    }

    @Override
    public WorldWeather resolve(String arg, String strategy)
    {
        WorldWeather weatherMode = WorldWeather.getByAlias(arg);

        if (weatherMode == null)
        {
            throw new ArgumentResolutionException(MessageUtils.parse("Invalid weather type: <type>", Placeholder.unparsed("type", arg)));
        }

        return weatherMode;
    }
    
}
