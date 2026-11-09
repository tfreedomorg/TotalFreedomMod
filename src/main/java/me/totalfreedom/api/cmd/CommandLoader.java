package me.totalfreedom.api.cmd;

import me.totalfreedom.totalfreedommod.ProtectArea;

import me.totalfreedom.api.FreedomAPI;

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
import me.totalfreedom.totalfreedommod.cmd.Command_totalfreedommod;
import me.totalfreedom.totalfreedommod.cmd.CommandRegistry;
import me.totalfreedom.totalfreedommod.cmd.internal.*;
import me.totalfreedom.totalfreedommod.cmd.resolver.*;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.api.world.WorldTime;
import me.totalfreedom.api.world.WorldWeather;

import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Registers the custom argument resolvers into the {@link ResolverRegistry} and auto-discovers {@link FCommand} declarations.
 */
public class CommandLoader extends FreedomService
{

    private boolean started = false;

    public CommandLoader(FreedomAPI plugin) 
    {
        super(plugin);
    }

    @Override
    public void onStart() 
    {
        if (started) 
        {
            return;
        }
        started = true;

        registerResolvers();

        int loaded = load(Command_totalfreedommod.class);
        FLog.info("Loaded {} commands.", loaded);
    }

    @Override
    public void onStop()
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
            () -> plugin.services().require(ProtectArea.class).getProtectedAreaNames());
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

    /**
     * Discovers and registers every {@code Command_*} class living alongside {@code anchor}'s own
     * package, using {@code anchor}'s own defining classloader and code source rather than TFM's.
     * Pass one of TFM's own command classes to load its built-ins, or a command class from a
     * plugin that depends on TFM to register that plugin's commands the same way: the rest of its
     * package is discovered from that one class. Command classes must still follow the
     * {@code Command_<identifier>} naming convention to be picked up.
     *
     * @param anchor A representative command class from the package to scan.
     * @return The number of commands registered.
     */
    public int load(Class<? extends FCommand> anchor)
    {
        ClassLoader classLoader = anchor.getClassLoader();
        String packageName = anchor.getPackageName();

        try
        {
            Path codeSource = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());

            if (Files.isDirectory(codeSource))
            {
                try (final Stream<Path> walk = Files.walk(codeSource.resolve(packageName.replace('.', '/'))))
                {
                    return countLoaded(walk, packageName, classLoader);
                }
            }

            try (final FileSystem zipFs = FileSystems.newFileSystem(codeSource);
                 final Stream<Path> walk = Files.walk(zipFs.getPath("/" + packageName.replace('.', '/'))))
            {
                return countLoaded(walk, packageName, classLoader);
            }
        }
        catch (Exception ex)
        {
            FLog.warn("Error walking commands: \n{}", ExceptionUtils.getRootCauseMessage(ex), ex);
        }

        return 0;
    }

    private int countLoaded(Stream<Path> walk, String packageName, ClassLoader classLoader)
    {
        return (int) walk
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("Command_") && name.endsWith(".class"))
                .map(name -> packageName + "." + name.substring(0, name.length() - ".class".length()))
                .filter(className -> loadCommandClass(className, classLoader))
                .count();
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
            FLog.warn("Could not load command class {}: \n{}", className, ExceptionUtils.getRootCauseMessage(ex));
        } 
        catch (Exception ex) 
        {
            FLog.warn("Failed to register command {}: \n{}", className, ExceptionUtils.getRootCauseMessage(ex));
        }
        return false;
    }
}
