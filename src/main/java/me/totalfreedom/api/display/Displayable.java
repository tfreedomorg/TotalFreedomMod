package me.totalfreedom.api.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
        return LegacyComponentSerializer.legacyAmpersand().serialize(getColoredName());
    }

    @Deprecated
    public default String getColoredTagLegacy()
    {
        return LegacyComponentSerializer.legacyAmpersand().serialize(getColoredTag());
    }

    @Deprecated
    public default String getColoredLoginMessageLegacy()
    {
        return LegacyComponentSerializer.legacyAmpersand().serialize(getColoredLoginMessage());
    }

}
