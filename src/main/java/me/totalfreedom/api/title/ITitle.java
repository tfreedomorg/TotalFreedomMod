package me.totalfreedom.api.title;

import me.totalfreedom.api.display.Displayable;

import java.util.Set;

public interface ITitle extends Displayable
{
    String getId();
    int getWeight();
    String getDeterminer();
    String getAbbreviation();
    String getPrefix();
    Set<String> getPermissions();
    boolean isAnnounce();
    boolean grants(String permission);
}
