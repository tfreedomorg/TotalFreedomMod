package me.totalfreedom.totalfreedommod.player;

import java.util.Locale;

/**
 * Filter shared by the spy services deciding whose activity a spy is shown.
 */
public enum SpyMode
{
	OFF,
	OPS,
	ADMINS,
	ALL;

	public static SpyMode fromStorage(String value)
	{
		if (value == null) return OFF;

		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex)
		{
			return OFF;
		}
	}

	public boolean shows(boolean senderIsAdmin)
	{
		return switch (this)
		{
		case OFF -> false;
		case OPS -> !senderIsAdmin;
		case ADMINS -> senderIsAdmin;
		case ALL -> true;
		};
	}

	public String getName()
	{
		return name().toLowerCase(Locale.ROOT);
	}
}