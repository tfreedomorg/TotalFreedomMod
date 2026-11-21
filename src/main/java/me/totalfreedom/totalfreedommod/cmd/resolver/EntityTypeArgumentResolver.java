package me.totalfreedom.totalfreedommod.cmd.resolver;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

public class EntityTypeArgumentResolver implements AbstractArgumentResolver<EntityType>
{
    @Override
    public String name()
    {
        return "EntityType";
    }

    @Override
    public EntityType resolve(String arg, String strategy)
    {
        try
        {
            final Key key = arg.contains(":") ? Key.key(arg.toLowerCase()) : NamespacedKey.minecraft(arg.toLowerCase()).key();
            final EntityType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key);

            if (type == null)
            {
                throw new ArgumentResolutionException("Invalid entity type: " + arg);
            }

            switch (strategy.toLowerCase())
            {
                case "alive" ->
                {
                    if (!type.isAlive())
                    {
                        throw new ArgumentResolutionException(type.key().asString() + " is a valid entity, however it is not alive.");
                    }
                }
                case "spawnable" ->
                {
                    if (!type.isSpawnable())
                    {
                        throw new ArgumentResolutionException(type.key().asString() + " is a valid entity, however it can't be spawned");
                    }
                }
                case "mobs" ->
                {
                    if (!type.isAlive() || !type.isSpawnable() || Player.class.equals(type.getEntityClass()))
                    {
                        throw new ArgumentResolutionException(type.key().asString() + " is a valid entity, however it is not a mob.");
                    }
                }
            }

            return type;
        }
        catch (InvalidKeyException | IllegalArgumentException ex)
        {
            throw new ArgumentResolutionException("Invalid entity type key: " + arg);
        }
    }
}
