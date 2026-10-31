package me.totalfreedom.totalfreedommod;

import java.io.File;
import java.util.Optional;

import me.totalfreedom.api.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.LoggerFactory;

import me.totalfreedom.api.banning.IBanManager;
import me.totalfreedom.api.blocking.item.IItemValidator;
import me.totalfreedom.api.blocking.sweep.ISweepScheduler;
import me.totalfreedom.api.cmd.CommandLoader;
import me.totalfreedom.api.config.IMainConfig;
import me.totalfreedom.api.economy.IBank;
import me.totalfreedom.api.economy.IEcoManager;
import me.totalfreedom.api.framework.IServiceManager;
import me.totalfreedom.api.player.IPlayerData;
import me.totalfreedom.api.player.IPlayerList;
import me.totalfreedom.api.rank.IConsoleSenderRegistry;
import me.totalfreedom.api.rank.IRankManager;
import me.totalfreedom.api.sql.IFreedomDatabase;
import me.totalfreedom.api.title.ITitleManager;
import me.totalfreedom.api.vanish.IVanishService;
import me.totalfreedom.api.world.IGenerationService;
import me.totalfreedom.api.world.IWorldManager;
import me.totalfreedom.totalfreedommod.banning.PermbanList;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.MethodTimer;

/**
 * Connects {@link FreedomAggregate}, the composition root that owns every service and its
 * lifecycle, to Bukkit's plugin lifecycle. Implements {@link FreedomAPI} by delegating to the
 * aggregate; holds no service state of its own.
 */
public class TotalFreedomMod extends JavaPlugin implements FreedomAPI
{

    public static final String CONFIG_FILENAME = "config.yml";

    private FreedomAggregate aggregate;

    @Override
    public void onLoad()
    {
        FLog.setPluginLogger(getSLF4JLogger());
        FLog.setServerLogger(LoggerFactory.getLogger("Minecraft-Server"));

        // Register here; PluginProvider#get() then calls from the services manager itself rather than storing its own atomic reference.
        Bukkit.getServicesManager().register(FreedomAPI.class, this, this, ServicePriority.Highest);

        aggregate = new FreedomAggregate(this);
        aggregate.load();
    }

    @Override
    public void onEnable()
    {
        FLog.info("Created by Madgeek1450 and Prozza");
        FLog.info("Version " + aggregate.buildInfo().formattedVersion());
        FLog.info("Compiled " + aggregate.buildInfo().date() + " by " + aggregate.buildInfo().author());

        final MethodTimer timer = new MethodTimer();
        timer.start();

        // Delete unused files
        FUtil.deleteCoreDumps();
        FUtil.deleteFolder(new File("./_deleteme"));

        BackupManager backups = new BackupManager(this);
        backups.createBackups(TotalFreedomMod.CONFIG_FILENAME, true);
        backups.createBackups(PermbanList.CONFIG_FILENAME);

        aggregate.enable();

        timer.update();
        FLog.info("Version " + getPluginMeta().getVersion() + " enabled in " + timer.getTotal() + "ms");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id)
    {
        if (aggregate != null && aggregate.generation() != null)
        {
            final Optional<ChunkGenerator> generator = aggregate.generation().generatorFor(worldName);

            if (generator.isPresent())
                return generator.get();
        }

        return super.getDefaultWorldGenerator(worldName, id);
    }

    @Override
    public void onDisable()
    {
        if (aggregate != null)
        {
            aggregate.disable();
        }

        getServer().getScheduler().cancelTasks(this);

        FLog.info("Plugin disabled");

        // The services manager unregisters us on disable; only the cached read needs clearing.
        PluginProvider.release();
    }

    @Override
    public IServiceManager services()
    {
        return aggregate.services();
    }

    @Override
    public IServiceManager bridges()
    {
        return aggregate.bridges();
    }

    @Override
    public CommandLoader commands()
    {
        return aggregate.commands();
    }

    @Override
    public IMainConfig config()
    {
        return aggregate.config();
    }

    @Override
    public BuildInfo buildInfo()
    {
        return aggregate.buildInfo();
    }

    @Override
    public IFreedomDatabase database()
    {
        return aggregate.database();
    }

    @Override
    public IPlayerList players()
    {
        return aggregate.players();
    }

    @Override
    public IBank bank()
    {
        return aggregate.bank();
    }

    @Override
    public IEcoManager economy()
    {
        return aggregate.economy();
    }

    @Override
    public IRankManager ranks()
    {
        return aggregate.ranks();
    }

    @Override
    public ITitleManager titles()
    {
        return aggregate.titles();
    }

    @Override
    public IWorldManager worlds()
    {
        return aggregate.worlds();
    }

    @Override
    public IGenerationService generation()
    {
        return aggregate.generation();
    }

    @Override
    public IVanishService vanish()
    {
        return aggregate.vanish();
    }

    @Override
    public IItemValidator itemValidator()
    {
        return aggregate.itemValidator();
    }

    @Override
    public IConsoleSenderRegistry consoleSenders()
    {
        return aggregate.consoleSenders();
    }

    @Override
    public IBanManager bans()
    {
        return aggregate.bans();
    }

    @Override
    public IAutoEject autoEject()
    {
        return aggregate.autoEject();
    }

    @Override
    public ISweepScheduler sweepScheduler()
    {
        return aggregate.sweepScheduler();
    }

    @Override
    public IGameRuleHandler gameRules()
    {
        return aggregate.gameRules();
    }

    @Override
    public PlayerData getPlayerData(Player target)
    {
        return players().getData(target);
    }

    /**
     * {@link PlayerData} is the only implementation, so anything else can't have come from this
     * plugin and is ignored.
     */
    @Override
    public void savePlayerData(IPlayerData data)
    {
        if (data instanceof PlayerData playerData)
            players().saveData(playerData);
    }

    @Override
    public boolean isAdmin(CommandSender sender)
    {
        return ranks().isAdmin(sender);
    }
}
