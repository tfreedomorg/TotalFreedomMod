package me.totalfreedom.totalfreedommod.vault;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.milkbowl.vault.permission.Permission;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.rank.CustomRank;


public class PermissionService extends Permission {
	private final TotalFreedomMod plugin;

	public PermissionService(TotalFreedomMod plugin) {
		this.plugin = plugin;
	}

	@Override
	public String getName() {
		return "TotalFreedomMod";
	}

	@Override
	public boolean isEnabled() {
		return plugin.getServer().getPluginManager().isPluginEnabled(plugin);
	}

	@Override
	public boolean hasSuperPermsCompat() {
		return true;
	}

	@Override
	public boolean playerHas(String world, String player, String permission) {
		Player p = plugin.getServer().getPlayerExact(player);
		if (p != null && p.isOnline()) {
			return p.hasPermission(permission);
		}
		return false;
	}

	@Override
	public boolean playerAdd(String world, String player, String permission) {
		return false;
	}

	@Override
	public boolean playerRemove(String world, String player, String permission) {
		return false;
	}

	@Override
	public boolean groupHas(String world, String group, String permission) {
		return false;
	}

	@Override
	public boolean groupAdd(String world, String group, String permission) {
		return false;
	}

	@Override
	public boolean groupRemove(String world, String group, String permission) {
		return false;
	}

	@Override
	public boolean playerInGroup(String world, String player, String group) {
		Player p = plugin.getServer().getPlayerExact(player);
		if (p != null && p.isOnline()) {
			CustomRank rank = plugin.rm.getEffectiveRank(p);
			return rank != null && rank.getId().equalsIgnoreCase(group);
		}
		return false;
	}

	@Override
	public boolean playerAddGroup(String world, String player, String group) {
		return false;
	}

	@Override
	public boolean playerRemoveGroup(String world, String player, String group) {
		return false;
	}

	@Override
	public String[] getPlayerGroups(String world, String player) {
		Player p = plugin.getServer().getPlayerExact(player);
		if (p != null && p.isOnline()) {
			CustomRank rank = plugin.rm.getEffectiveRank(p);
			if (rank != null) {
				return new String[] { rank.getId() };
			}
		}
		return new String[0];
	}

	@Override
	public String getPrimaryGroup(String world, String player) {
		Player p = plugin.getServer().getPlayerExact(player);
		if (p != null && p.isOnline()) {
			CustomRank rank = plugin.rm.getEffectiveRank(p);
			if (rank != null) {
				return rank.getId();
			}
		}
		return "default";
	}

	@Override
	public String[] getGroups() {
		// Groups are whatever ranks.json defines, so an operator-defined rank is visible to Vault
		// consumers on the same footing as one the plugin ships with.
		return plugin.rm.getCustomRanks().keySet().toArray(String[]::new);
	}

	@Override
	public boolean hasGroupSupport() {
		return true;
	}

	@Override
	public String getPrimaryGroup(String world, OfflinePlayer player) {
		Player p = plugin.getServer().getPlayer(player.getUniqueId());
		if (p != null && p.isOnline()) {
			CustomRank rank = plugin.rm.getEffectiveRank(p);
			if (rank != null) {
				return rank.getId();
			}
		}
		return "default";
	}

	@Override
	public String[] getPlayerGroups(String world, OfflinePlayer player) {
		Player p = plugin.getServer().getPlayer(player.getUniqueId());
		if (p != null && p.isOnline()) {
			CustomRank rank = plugin.rm.getEffectiveRank(p);
			if (rank != null) {
				return new String[] { rank.getId() };
			}
		}
		return new String[0];
	}
}
