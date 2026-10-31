package me.totalfreedom.api.blocking.item;

import org.bukkit.inventory.ItemStack;

public interface IItemValidator
{
	/**
	 * Whether TFM's item scan rejects {@code item}. While panic mode is on, this rejects
	 * every non empty item.
	 */
	boolean isCursed(ItemStack item);
}