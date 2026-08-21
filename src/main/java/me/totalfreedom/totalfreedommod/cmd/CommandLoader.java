package me.totalfreedom.totalfreedommod.cmd;

import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.key.Key;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.internal.*;
import me.totalfreedom.totalfreedommod.cmd.resolver.*;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.world.WorldTime;
import me.totalfreedom.totalfreedommod.world.WorldWeather;

import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Registers the custom argument resolvers into the {@link ResolverRegistry} and auto-discovers {@link FCommand} declarations.
 */
public class CommandLoader extends FreedomService 
{

    private static final String COMMANDS_PACKAGE = "me.totalfreedom.totalfreedommod.cmd";

    private boolean started = false;

    public CommandLoader(TotalFreedomMod plugin) 
    {
        super(plugin);
    }

    @Override
    protected void onStart() 
    {
        if (started) 
        {
            return;
        }
        started = true;

        registerResolvers();

        int loaded = discoverCommands();
        FLog.info("Loaded " + loaded + " commands.");
    }

    @Override
    protected void onStop()
    {
        started = false;

        CommandRegistry.clear();
        CommandProcessor.reset();
        CooldownManager.clearAll();
        ResolverRegistry.clear();
    }

    /**
     * Registers the shared argument resolvers. Types with a binding resolve automatically
     * by handler parameter type and gain default tab-completion candidates; the rest are
     * available by name via {@code @Resolve("<name>")}. Scalars (int/double/boolean) and
     * online players keep their native Brigadier paths and are registered by name only.
     */
    private void registerResolvers() 
    {
        ResolverRegistry.register(new MaterialArgumentResolver(), Material.class,
            memoize(() -> Registry.MATERIAL.stream().map(m -> m.key().value()).sorted().toList()));
        ResolverRegistry.register(new EnchantmentArgumentResolver(), Enchantment.class,
            memoize(() -> RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .stream().map(e -> e.key().value()).sorted().toList()));
        ResolverRegistry.register(new PotionEffectTypeArgumentResolver(), PotionEffectType.class,
            memoize(() -> Registry.POTION_EFFECT_TYPE.stream().map(p -> p.key().value()).sorted().toList()));
        ResolverRegistry.register(new EntityTypeArgumentResolver(), EntityType.class,
            memoize(() -> RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE)
                .stream().map(e -> e.key().value()).sorted().toList()));
        ResolverRegistry.register(new KeyArgumentResolver(), Key.class);
        ResolverRegistry.register(new OfflinePlayerArgumentResolver(), OfflinePlayer.class);
        ResolverRegistry.register(new PluginArgumentResolver(), Plugin.class,
            () -> Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(Plugin::getName).sorted().toList());
        ResolverRegistry.register(new DateOffsetArgumentResolver(), Date.class);
        ResolverRegistry.register(new InetAddressResolver(), InetAddress.class);
        ResolverRegistry.register(new MaterialQueryArgumentProvider());
        ResolverRegistry.register(new PlayerArgumentResolver());
        ResolverRegistry.register(new PlayerListArgumentResolver());
        ResolverRegistry.register(new InetAddressListResolver());
        ResolverRegistry.register(new EnumArgumentResolver());
        ResolverRegistry.register(new BooleanArgumentResolver());
        ResolverRegistry.register(new IntegerArgumentResolver());
        ResolverRegistry.register(new DoubleArgumentResolver());
        ResolverRegistry.register(new FloatArgumentResolver());
        ResolverRegistry.register(new WorldTimeArgumentResolver(), WorldTime.class);
        ResolverRegistry.register(new WeatherArgumentResolver(), WorldWeather.class);
        ResolverRegistry.register(new WorldArgumentResolver(), World.class,
            () -> Bukkit.getWorlds().stream().map(World::getName).sorted().toList());
        // Suggestions are not memoized because regions change at runtime.
        ResolverRegistry.register(new ProtectedRegionArgumentResolver(plugin), ProtectedRegion.class,
            () -> plugin.pa.getProtectedAreaNames());
    }

    private static Supplier<List<String>> memoize(Supplier<List<String>> source) 
    {
        return new Supplier<>() 
        {
            private volatile List<String> cached;

            @Override
            public List<String> get() 
            {
                List<String> local = cached;
                if (local == null) 
                {
                    cached = local = source.get();
                }
                return local;
            }
        };
    }

    private int discoverCommands() 
    {
        ClassLoader classLoader = plugin.getClass().getClassLoader();

        try (final FileSystem zipFs = FileSystems.newFileSystem(Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
             final Stream<Path> walk = Files.walk(zipFs.getPath("/" + getClass().getPackageName().replaceAll("\\.", "/"))))
        {
            return (int) walk
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("Command_") && name.endsWith(".class"))
                    .map(name -> COMMANDS_PACKAGE + "." + name.substring(0, name.length() - ".class".length()))
                    .filter(className -> loadCommandClass(className, classLoader))
                    .count();
        }
        catch (Exception ex) 
        {
            FLog.warning(String.format("Error walking commands: \n%s", ExceptionUtils.getRootCauseMessage(ex)));
            FLog.warning(ex);
        }

        return 0;
    }

    private boolean loadCommandClass(String className, ClassLoader classLoader) 
    {
        try 
        {
            Class<?> clazz = classLoader.loadClass(className);

            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()) || !FCommand.class.isAssignableFrom(clazz)) 
                return false;

            FCommand instance = (FCommand) clazz.getDeclaredConstructor().newInstance();
            CommandHolder.register(instance);
            return true;
        } 
        catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError ex) 
        {
            FLog.warning(String.format("Could not load command class %s: \n%s", className, ExceptionUtils.getRootCauseMessage(ex)));
        } 
        catch (Exception ex) 
        {
            FLog.warning(String.format("Failed to register command %s: \n%s ", className, ExceptionUtils.getRootCauseMessage(ex)));
        }
        return false;
    }
}
