package me.totalfreedom.totalfreedommod.cmd.resolver;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

public class EnchantmentArgumentResolver implements AbstractArgumentResolver<Enchantment>
{

    @Override
    public String name()
    {
        return "Enchantment";
    }

    @Override
    public Enchantment resolve(String arg, String strategy)
    {
        try
        {
            final Key key = arg.contains(":") ? Key.key(arg.toLowerCase()) : NamespacedKey.minecraft(arg.toLowerCase()).key();
            final Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);

            if (enchantment == null)
            {
                throw new ArgumentResolutionException("Invalid enchantment: " + arg);
            }

            return enchantment;
        }
        catch (InvalidKeyException | IllegalArgumentException ex)
        {
            throw new ArgumentResolutionException("Invalid enchantment key: " + arg);
        }
    }
}
