package me.totalfreedom.totalfreedommod.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public interface Displayable
{

    public String getName();

    public String getTag();

    public NamedTextColor getColor();

    public Component getColoredName();

    public Component getColoredTag();

    public Component getColoredLoginMessage();

    @Deprecated
    public default String getColoredNameLegacy()
    {
        return me.totalfreedom.totalfreedommod.util.AdventureUtil.componentToLegacy(getColoredName());
    }

    @Deprecated
    public default String getColoredTagLegacy()
    {
        return me.totalfreedom.totalfreedommod.util.AdventureUtil.componentToLegacy(getColoredTag());
    }

    @Deprecated
    public default String getColoredLoginMessageLegacy()
    {
        return me.totalfreedom.totalfreedommod.util.AdventureUtil.componentToLegacy(getColoredLoginMessage());
    }

}
