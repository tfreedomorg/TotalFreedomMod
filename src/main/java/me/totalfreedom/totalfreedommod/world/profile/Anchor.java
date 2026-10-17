package me.totalfreedom.totalfreedommod.world.profile;

/**
 * Where a feature wants to be placed, which decides how the populator picks its y.
 * <p>
 * SURFACE sits on the column top, so trees and grass land on the ground. RANGE picks a y somewhere
 * inside the feature's own minY and maxY, which is what ores want. Either way minY and maxY still
 * filter, so a surface feature can say it never appears above a certain height.
 */
public enum Anchor
{
    SURFACE,
    RANGE
}
