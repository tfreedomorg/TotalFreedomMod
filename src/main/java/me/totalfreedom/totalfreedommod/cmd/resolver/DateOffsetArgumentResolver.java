package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.Date;
import java.util.List;

import me.totalfreedom.totalfreedommod.util.FUtil;

public class DateOffsetArgumentResolver implements AbstractArgumentResolver<Date>
{

    /**
     * A duration is free-form, so there is no candidate set to enumerate. These are the shapes
     * {@link FUtil#parseDateOffset} accepts, offered so the argument completes to something rather
     * than staying blank.
     */
    public static final List<String> COMMON_OFFSETS = List.of(
            "30s", "5m", "15m", "30m", "1h", "6h", "12h", "1d", "3d", "1w", "2w", "1mo", "1y");

    @Override
    public String name()
    {
        return "DateOffset";
    }

    @Override
    public List<String> suggestions()
    {
        return COMMON_OFFSETS;
    }

    @Override
    public Date resolve(String arg, String strategy)
    {
        final Date date = FUtil.parseDateOffset(arg);

        if (date == null)
        {
            throw new ArgumentResolutionException("Invalid duration '" + arg
                    + "'. Use <number><unit> with s/m/h/d/w/mo/y, chained if you like: 30s, 5m, 2h, 1d, 1h30m, 2d7h45m31s");
        }

        return date;
    }
}
