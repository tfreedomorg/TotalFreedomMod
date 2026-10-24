package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.Arrays;
import java.util.Map;

import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.ImmutableMap;

public interface AbstractParameterizedArgumentResolver<T> extends AbstractArgumentResolver<T>
{
    T resolve(String arg, Map<String, Object> parameters);

    @Override
    default T resolve(String arg, String strategy)
    {
        return resolve(arg, readStrategyArguments(strategy));
    }

    static Map<String, Object> readStrategyArguments(String strategy)
    {
        return Arrays.stream((strategy != null ? strategy : "").split(","))
                .map(entry -> entry.split("="))
                .peek(split ->
                {
                    if (split.length == 0 || split.length > 2)
                    {
                        throw new IllegalArgumentException("Malformed parameter set: "  + Arrays.toString(split));
                    }
                })
                .collect(ImmutableMap.toImmutableMap(split -> split[0],
                        split -> split.length == 1 ? true : FUtil.stringToObject(split[1])));
    }
}
