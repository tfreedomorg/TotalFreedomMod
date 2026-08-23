package me.totalfreedom.totalfreedommod;

import java.io.InputStream;
import java.util.Properties;

import me.totalfreedom.api.BuildInfo;
import me.totalfreedom.api.IAutoEject;
import me.totalfreedom.api.IGameRuleHandler;
import me.totalfreedom.api.admin.IAdminList;
import me.totalfreedom.api.banning.IBanManager;
import me.totalfreedom.api.blocking.sweep.ISweepScheduler;
import me.totalfreedom.api.config.IMainConfig;
import me.totalfreedom.api.economy.IBank;
import me.totalfreedom.api.economy.IEcoManager;
import me.totalfreedom.api.framework.IServiceManager;
import me.totalfreedom.api.player.IPlayerList;
import me.totalfreedom.api.rank.IConsoleSenderRegistry;
import me.totalfreedom.api.rank.IRankManager;
import me.totalfreedom.api.sql.IFreedomDatabase;
import me.totalfreedom.api.title.ITitleManager;
import me.totalfreedom.api.vanish.IVanishService;
import me.totalfreedom.api.world.IGenerationService;
import me.totalfreedom.api.world.IWorldManager;
import me.totalfreedom.totalfreedommod.admin.AdminList;
import me.totalfreedom.totalfreedommod.banning.BanManager;
import me.totalfreedom.totalfreedommod.banning.PermbanList;
import me.totalfreedom.totalfreedommod.banning.StrikeList;
import me.totalfreedom.totalfreedommod.blocking.*;
import me.totalfreedom.totalfreedommod.blocking.command.CommandBlocker;
import me.totalfreedom.totalfreedommod.blocking.entity.*;
import me.totalfreedom.totalfreedommod.blocking.item.ConsoleSpamFilter;
import me.totalfreedom.totalfreedommod.blocking.item.ItemValidator;
import me.totalfreedom.totalfreedommod.blocking.packet.CrashPacketService;
import me.totalfreedom.totalfreedommod.blocking.sign.SignValidator;
import me.totalfreedom.totalfreedommod.blocking.spawner.SpawnerValidator;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepScheduler;
import me.totalfreedom.totalfreedommod.bridge.CoreProtectBridge;
import me.totalfreedom.totalfreedommod.bridge.EssentialsBridge;
import me.totalfreedom.totalfreedommod.bridge.LibsDisguisesBridge;
import me.totalfreedom.totalfreedommod.bridge.WorldEditBridge;
import me.totalfreedom.totalfreedommod.caging.Cager;
import me.totalfreedom.api.cmd.CommandLoader;
import me.totalfreedom.totalfreedommod.config.MainConfig;
import me.totalfreedom.totalfreedommod.discord.DiscordBridge;
import me.totalfreedom.totalfreedommod.disguise.DisallowedDisguises;
import me.totalfreedom.totalfreedommod.eco.EcoManager;
import me.totalfreedom.totalfreedommod.framework.ServiceManager;
import me.totalfreedom.totalfreedommod.freeze.Freezer;
import me.totalfreedom.totalfreedommod.fun.*;
import me.totalfreedom.totalfreedommod.httpd.HTTPDaemon;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.PlayerList;
import me.totalfreedom.totalfreedommod.rank.ConsoleSenderRegistry;
import me.totalfreedom.totalfreedommod.rank.RankManager;
import me.totalfreedom.totalfreedommod.sql.FreedomDatabase;
import me.totalfreedom.totalfreedommod.ssh.SshDaemon;
import me.totalfreedom.totalfreedommod.tablist.TabList;
import me.totalfreedom.totalfreedommod.title.TitleManager;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.vanish.VanishService;
import me.totalfreedom.totalfreedommod.world.GenerationService;
import me.totalfreedom.totalfreedommod.world.WorldManager;

/**
 * Composition root for the plugin. Loads configuration, registers every service and bridge in
 * dependency order, and exposes the curated {@code FreedomAPI} view over them.
 * {@link TotalFreedomMod} only connects this to Bukkit's plugin lifecycle.
 */
public class FreedomAggregate
{

    private final TotalFreedomMod plugin;
    private final ServiceManager services;
    private final ServiceManager bridges;

    private BuildInfo buildInfo;
    private MainConfig config;
    private ConsoleSenderRegistry consoleSenderRegistry;

    public FreedomAggregate(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
        this.services = new ServiceManager(plugin);
        this.bridges = new ServiceManager(plugin);
    }

    /**
     * Loads build metadata. Called from {@code onLoad()}, before any service exists.
     */
    public void load()
    {
        buildInfo = loadBuildInfo();
    }

    /**
     * Loads configuration, runs one-time migrations, and registers/starts every service and
     * bridge in dependency order. Called from {@code onEnable()}.
     */
    public void enable()
    {
        config = new MainConfig(plugin);
        config.load();
        FPlayer.refreshConfig();

        ConfigConverter configConverter = new ConfigConverter(plugin);
        configConverter.convertRanksYaml();
        config.load();
        FPlayer.refreshConfig();

        services.register(FreedomDatabase.class, FreedomDatabase::new);
        services.register(SavedFlags.class, SavedFlags::new);
        services.register(WorldManager.class, WorldManager::new);
        services.register(GenerationService.class, GenerationService::new);
        services.register(AdminList.class, AdminList::new);

        configConverter.convertAdminConsoleRanks();

        services.register(RankManager.class, RankManager::new);
        services.register(TitleManager.class, TitleManager::new);

        configConverter.convertCosmeticRankHolders();

        consoleSenderRegistry = new ConsoleSenderRegistry(plugin);
        consoleSenderRegistry.load();

        services.register(CommandLoader.class, CommandLoader::new);
        services.register(CommandBlocker.class, CommandBlocker::new);
        services.register(SweepScheduler.class, SweepScheduler::new);
        services.register(ItemValidator.class, ItemValidator::new);
        services.register(ConsoleSpamFilter.class, ConsoleSpamFilter::new);
        services.register(SignValidator.class, SignValidator::new);
        services.register(CrashPacketService.class, CrashPacketService::new);
        services.register(EventBlocker.class, EventBlocker::new);
        services.register(BlockBlocker.class, BlockBlocker::new);
        services.register(MobBlocker.class, MobBlocker::new);
        services.register(EntityNameValidator.class, EntityNameValidator::new);
        services.register(EntitySizeGuard.class, EntitySizeGuard::new);
        services.register(SpawnerValidator.class, SpawnerValidator::new);
        services.register(ProjectileGuard.class, ProjectileGuard::new);
        services.register(TextDisplayGuard.class, TextDisplayGuard::new);
        services.register(WaypointGuard.class, WaypointGuard::new);
        services.register(InteractBlocker.class, InteractBlocker::new);
        services.register(PotionBlocker.class, PotionBlocker::new);
        services.register(LoginProcess.class, LoginProcess::new);
        services.register(AntiNuke.class, AntiNuke::new);
        services.register(AntiDrop.class, AntiDrop::new);
        services.register(AntiSpam.class, AntiSpam::new);

        services.register(PlayerList.class, PlayerList::new);
        services.register(EcoManager.class, EcoManager::new);
        services.register(JoinLeaveMessages.class, JoinLeaveMessages::new);
        services.register(Announcer.class, Announcer::new);
        services.register(ChatReaction.class, ChatReaction::new);
        services.register(ChatManager.class, ChatManager::new);
        services.register(TextFilterService.class, TextFilterService::new);
        services.register(BanManager.class, BanManager::new);
        services.register(PermbanList.class, PermbanList::new);
        services.register(StrikeList.class, StrikeList::new);
        services.register(ProtectArea.class, ProtectArea::new);
        services.register(SpawnManager.class, SpawnManager::new);
        services.register(GameRuleHandler.class, GameRuleHandler::new);
        services.register(GameModeGuard.class, GameModeGuard::new);
        services.register(DisallowedDisguises.class, DisallowedDisguises::new);

        // Single admin utils
        services.register(CommandSpy.class, CommandSpy::new);
        services.register(PotionSpy.class, PotionSpy::new);
        services.register(SignSpy.class, SignSpy::new);
        services.register(BookSpy.class, BookSpy::new);
        services.register(Cager.class, Cager::new);
        services.register(Freezer.class, Freezer::new);
        services.register(Orbiter.class, Orbiter::new);
        services.register(Muter.class, Muter::new);
        services.register(SpectatorBlocker.class, SpectatorBlocker::new);
        services.register(Fuckoff.class, Fuckoff::new);
        services.register(AutoEject.class, AutoEject::new);

        services.register(MovementValidator.class, MovementValidator::new);
        services.register(EntityWiper.class, EntityWiper::new);
        services.register(ServerPing.class, ServerPing::new);

        // Fun
        services.register(ItemFun.class, ItemFun::new);
        services.register(Landminer.class, Landminer::new);
        services.register(MP44.class, MP44::new);
        services.register(Jumppads.class, Jumppads::new);
        services.register(Trailer.class, Trailer::new);

        // HTTPD
        services.register(HTTPDaemon.class, HTTPDaemon::new);

        // SSH
        services.register(SshDaemon.class, SshDaemon::new);

        // Discord
        services.register(DiscordBridge.class, DiscordBridge::new);

        // Vanish
        services.register(TabList.class, TabList::new);
        services.register(VanishService.class, VanishService::new); // must register after jlm; see VanishService#onPlayerQuit

        // Start All Services
        services.start();

        // Start bridges
        bridges.register(CoreProtectBridge.class, CoreProtectBridge::new);
        bridges.register(EssentialsBridge.class, EssentialsBridge::new);
        bridges.register(LibsDisguisesBridge.class, LibsDisguisesBridge::new);
        bridges.register(WorldEditBridge.class, WorldEditBridge::new);
        bridges.start();
    }

    /**
     * Stops bridges, then services, in that order. Called from {@code onDisable()}.
     */
    public void disable()
    {
        bridges.stop();
        services.stop();
    }

    public IServiceManager services()
    {
        return services;
    }

    public IServiceManager bridges()
    {
        return bridges;
    }

    public CommandLoader commands()
    {
        return services.require(CommandLoader.class);
    }

    public IMainConfig config()
    {
        return config;
    }

    public BuildInfo buildInfo()
    {
        return buildInfo;
    }

    public IConsoleSenderRegistry consoleSenders()
    {
        return consoleSenderRegistry;
    }

    public IFreedomDatabase database()
    {
        return services.require(FreedomDatabase.class);
    }

    public IAdminList admins()
    {
        return services.require(AdminList.class);
    }

    public IPlayerList players()
    {
        return services.require(PlayerList.class);
    }

    public IBank bank()
    {
        return economy().bank();
    }

    public IEcoManager economy()
    {
        return services.require(EcoManager.class);
    }

    public IRankManager ranks()
    {
        return services.require(RankManager.class);
    }

    public ITitleManager titles()
    {
        return services.require(TitleManager.class);
    }

    public IWorldManager worlds()
    {
        return services.require(WorldManager.class);
    }

    public IGenerationService generation()
    {
        return services.require(GenerationService.class);
    }

    public IVanishService vanish()
    {
        return services.require(VanishService.class);
    }

    public IBanManager bans()
    {
        return services.require(BanManager.class);
    }

    public IAutoEject autoEject()
    {
        return services.require(AutoEject.class);
    }

    public ISweepScheduler sweepScheduler()
    {
        return services.require(SweepScheduler.class);
    }

    public IGameRuleHandler gameRules()
    {
        return services.require(GameRuleHandler.class);
    }

    private BuildInfo loadBuildInfo()
    {
        final Properties props = readResource("build.properties");
        final Properties gitprops = readResource("git.properties");

        final String author = props.getProperty("buildAuthor", "unknown");
        final String codename = props.getProperty("buildCodeName", "unknown");
        final String version = props.getProperty("buildVersion", plugin.getPluginMeta().getVersion());
        final String date = gitprops.getProperty("git.build.time", "unknown");
        final String head = gitprops.getProperty("git.commit.id.abbrev", "unknown");

        return new BuildInfo(author, codename, version, date, head);
    }

    private Properties readResource(String name)
    {
        final Properties properties = new Properties();
        try (InputStream in = plugin.getResource(name))
        {
            if (in != null)
            {
                properties.load(in);
            }
            else
            {
                FLog.warn("Build resource '" + name + "' not found in plugin jar; using defaults.");
            }
        }
        catch (Exception ex)
        {
            FLog.warn("Could not read build resource '" + name + "': " + ex.getMessage());
        }
        return properties;
    }
}
