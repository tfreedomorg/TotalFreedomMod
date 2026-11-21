package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.Arrays;
import java.util.List;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.world.WorldTime;

public class WorldTimeArgumentResolver implements AbstractArgumentResolver<WorldTime> 
{
    @Override
    public String name()
    {
        return "WorldTime";
    }

    /**
     * Aliases rather than constant names, because {@link WorldTime#getByAlias} only matches aliases.
     */
    @Override
    public List<String> suggestions()
    {
        return Arrays.stream(WorldTime.values())
                     .map(WorldTime::getAliases)
                     .flatMap(List::stream)
                     .sorted()
                     .toList();
    }

    @Override
    public WorldTime resolve(String arg, String strategy)
    {
        WorldTime resolved = WorldTime.getByAlias(arg);

        if (resolved == null)
        {
            throw new ArgumentResolutionException(MessageUtils.parse("Invalid world time: <type>", Placeholder.unparsed("type", arg)));
        }

        return resolved;
    }
}
