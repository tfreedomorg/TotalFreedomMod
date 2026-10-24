package me.totalfreedom.totalfreedommod.cmd.resolver;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

public class PotionEffectTypeArgumentResolver implements AbstractArgumentResolver<PotionEffectType>
{
    @Override
    public String name()
    {
        return "PotionEffectType";
    }

    @Override
    public PotionEffectType resolve(String arg, String strategy)
    {
        PotionEffectType type;

        // Try with numerical ID - this is probably going to stop working when Mojang eventually moves everything to
        //  namespaces like they did with Enchantments and Attributes
        try
        {
            final Integer id = Integer.parseInt(arg);
            type = PotionEffectType.getById(id);
        }
        catch (NumberFormatException ignored)
        {
            try
            {
                final Key key = arg.contains(":") ? Key.key(arg.toLowerCase()) : NamespacedKey.minecraft(arg.toLowerCase()).key();
                type = Registry.POTION_EFFECT_TYPE.get(key);
            }
            catch (InvalidKeyException | IllegalArgumentException ex)
            {
                throw new ArgumentResolutionException("Invalid potion effect key: " + ex);
            }
        }

        if (type == null)
        {
            throw new ArgumentResolutionException("Invalid potion type: " + arg);
        }

        return type;
    }
}
