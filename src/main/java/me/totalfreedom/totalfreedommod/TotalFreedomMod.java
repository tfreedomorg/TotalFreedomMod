package me.totalfreedom.totalfreedommod;

import me.totalfreedom.totalfreedommod.fun.Trailer;
import me.totalfreedom.totalfreedommod.tablist.TabList;
import me.totalfreedom.totalfreedommod.world.CleanroomChunkGenerator;
import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import me.totalfreedom.totalfreedommod.admin.AdminList;
import me.totalfreedom.totalfreedommod.banning.BanManager;
import me.totalfreedom.totalfreedommod.banning.PermbanList;
import me.totalfreedom.totalfreedommod.banning.StrikeList;
import me.totalfreedom.totalfreedommod.blocking.BlockBlocker;
import me.totalfreedom.totalfreedommod.blocking.EventBlocker;
import me.totalfreedom.totalfreedommod.blocking.InteractBlocker;
import me.totalfreedom.totalfreedommod.blocking.MobBlocker;
import me.totalfreedom.totalfreedommod.blocking.PotionBlocker;
import me.totalfreedom.totalfreedommod.blocking.command.CommandBlocker;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepScheduler;
import me.totalfreedom.totalfreedommod.blocking.entity.EntityNameValidator;
import me.totalfreedom.totalfreedommod.blocking.entity.EntitySizeGuard;
import me.totalfreedom.totalfreedommod.blocking.entity.TextDisplayGuard;
import me.totalfreedom.totalfreedommod.blocking.entity.ProjectileGuard;
import me.totalfreedom.totalfreedommod.blocking.entity.WaypointGuard;
import me.totalfreedom.totalfreedommod.blocking.item.ConsoleSpamFilter;
import me.totalfreedom.totalfreedommod.blocking.packet.CrashPacketService;
import me.totalfreedom.totalfreedommod.blocking.item.ItemValidator;
import me.totalfreedom.totalfreedommod.blocking.sign.SignValidator;
import me.totalfreedom.totalfreedommod.blocking.spawner.SpawnerValidator;
import me.totalfreedom.totalfreedommod.bridge.CoreProtectBridge;
import me.totalfreedom.totalfreedommod.bridge.EssentialsBridge;
import me.totalfreedom.totalfreedommod.bridge.LibsDisguisesBridge;
import me.totalfreedom.totalfreedommod.bridge.SimpleVoiceChatBridge;
import me.totalfreedom.totalfreedommod.bridge.WorldEditBridge;
import me.totalfreedom.totalfreedommod.caging.Cager;
import me.totalfreedom.totalfreedommod.cmd.CommandLoader;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.config.MainConfig;
import me.totalfreedom.totalfreedommod.discord.DiscordBridge;
import me.totalfreedom.totalfreedommod.freeze.Freezer;
import me.totalfreedom.totalfreedommod.fun.ItemFun;
import me.totalfreedom.totalfreedommod.fun.Jumppads;
import me.totalfreedom.totalfreedommod.fun.Landminer;
import me.totalfreedom.totalfreedommod.fun.MP44;
import me.totalfreedom.totalfreedommod.httpd.HTTPDaemon;
import me.totalfreedom.totalfreedommod.ssh.SshDaemon;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.PlayerBlockEnforcer;
import me.totalfreedom.totalfreedommod.player.PlayerBlockList;
import me.totalfreedom.totalfreedommod.player.PlayerList;
import me.totalfreedom.totalfreedommod.rank.ConsoleSenderRegistry;
import me.totalfreedom.totalfreedommod.rank.RankManager;
import me.totalfreedom.totalfreedommod.sql.FreedomDatabase;
import me.totalfreedom.totalfreedommod.sql.YamlMigrationService;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.MethodTimer;
import me.totalfreedom.totalfreedommod.framework.ServiceManager;
import me.totalfreedom.totalfreedommod.world.WorldManager;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public class TotalFreedomMod extends JavaPlugin
{

    public static final String CONFIG_FILENAME = "config.yml";
    //
    public static final BuildProperties build = new BuildProperties();
    //
    public static String pluginName;
    public static String pluginVersion;
    //
    public MainConfig config;
    //
    // Services
    public ServiceManager<TotalFreedomMod> services;
    public FreedomDatabase dm; // FreedomDatabase - Manages SQL database connections
    public SavedFlags sf; // SavedFlags - Stores saved flag states
    public WorldManager wm; // WorldManager - Manages world operations
    public AdminList al; // AdminList - Manages admin list and permissions
    public RankManager rm; // RankManager - Handles player ranks and display
    public ConsoleSenderRegistry csr; // ConsoleSenderRegistry - Maps console senders to appropriate rank
    // public CommandLoader cl; // CommandLoader - Loads and registers commands (LEGACY)
    public CommandLoader cmdl; // CmdLoader - Loads and registers Brigadier commands 
    public CommandBlocker cb; // CommandBlocker - Blocks specific commands
    public SweepScheduler sweepScheduler; // SweepScheduler - Shared budgeted world/chunk sweep walker
    public ItemValidator iv; // ItemValidator - Blocks unwanted NBT items
    public SignValidator sv; // SignValidator - Sanitizes the components of signs
    public CrashPacketService cps; // CrashPacketService - PacketEvents crash-protection hooks
    public EventBlocker eb; // EventBlocker - Blocks various game events
    public BlockBlocker bb; // BlockBlocker - Blocks block placement/breaking
    public MobBlocker mb; // MobBlocker - Blocks mob spawning
    public EntityNameValidator env; // EntityNameValidator - Strips custom names from entities
    public InteractBlocker ib; // InteractBlocker - Blocks block interactions
    public PotionBlocker pb; // PotionBlocker - Blocks potion effects
    public LoginProcess lp; // LoginProcess - Handles player login processing
    public AntiNuke nu; // AntiNuke - Prevents rapid command execution (nuking)
    public AntiDrop adr; // AntiDrop - Throttles item drop flooding
    public AntiSpam as; // AntiSpam - Prevents chat spam
    public PlayerList pl; // PlayerList - Manages player data and lists
    public PlayerBlockList pbl; // PlayerBlockList - Manages persistent per-player blocks
    public PlayerBlockEnforcer pbe; // PlayerBlockEnforcer - Applies player block relationships
    public JoinLeaveMessages jlm; // JoinLeaveMessages - Personal join/leave message filtering
    public Announcer an; // Announcer - Handles server announcements
    public ChatManager cm; // ChatManager - Manages chat formatting and admin chat
    public BanManager bm; // BanManager - Manages player bans
    public PermbanList pm; // PermbanList - Manages permanent ban list
    public StrikeList sl; // StrikeList - Tracks per-IP AutoEject strike counts
    public ProtectArea pa; // ProtectArea - Manages protected areas and spawnpoints
    public SpawnManager sm; // SpawnManager - Handles configured spawn behavior
    public GameRuleHandler gr; // GameRuleHandler - Manages game rules
    public CommandSpy cs; // CommandSpy - Logs and monitors command usage
    public PotionSpy ps; // PotionSpy - Logs and monitors potion usage
    public SignSpy ss; // SignSpy - Logs and monitors sign edits
    public Cager ca; // Cager - Creates cages around players
    public Freezer fm; // Freezer - Freezes players in place
    public Orbiter or; // Orbiter - Makes players orbit around a point
    public Muter mu; // Muter - Mutes players
    public SpectatorBlocker sb; // SpectatorBlocker - Blocks spectator teleports to players
    public Fuckoff fo; // Fuckoff - Kicks players with a message
    public AutoKick ak; // AutoKick - Automatically kicks players based on conditions
    public AutoEject ae; // AutoEject - Automatically ejects players from vehicles
    public MovementValidator mv; // MovementValidator - Validates player movement
    public EntityWiper ew; // EntityWiper - Wipes entities from the world
    public ServerPing sp; // ServerPing - Customizes server ping response
    public ItemFun it; // ItemFun - Fun item-related features
    public Landminer lm; // Landminer - Landmine functionality
    public MP44 mp; // MP44 - MP44 weapon functionality
    public Jumppads jp; // Jumppads - Jump pad functionality
    public Trailer tr; // Trailer - Trailer functionality
    public HTTPDaemon hd; // HTTPDaemon - HTTP server for web interface
    public SshDaemon sd; // SshDaemon - SSH server for remote console access
    public DiscordBridge db; // DiscordBridge - Built-in Discord chat/console relay
    public TabList tl; // TabList - Customizable tab list header, footer, and player names
    //
    // Bridges
    public ServiceManager<TotalFreedomMod> bridges;
    public CoreProtectBridge cpb;
    public EssentialsBridge esb; // EssentialsBridge - Bridge to Essentials plugin
    public SimpleVoiceChatBridge svcb; // SimpleVoiceChatBridge - Filters blocked players from voice chat
    public LibsDisguisesBridge ldb; // LibsDisguisesBridge - Bridge to LibsDisguises plugin
    public WorldEditBridge web; // WorldEditBridge - Bridge to WorldEdit plugin

    @Override
    public void onLoad()
    {
        PluginProvider.bind(this);
        TotalFreedomMod.pluginName = getPluginMeta().getName();
        TotalFreedomMod.pluginVersion = getPluginMeta().getVersion();

        FLog.setPluginLogger(getLogger());
        FLog.setServerLogger(getServer().getLogger());

        build.load(this);
    }

    @Override
    public void onEnable()
    {
        FLog.info("Created by Madgeek1450 and Prozza");
        FLog.info("Version " + build.formattedVersion());
        FLog.info("Compiled " + build.date + " by " + build.author);

        final MethodTimer timer = new MethodTimer();
        timer.start();

        // Delete unused files
        FUtil.deleteCoreDumps();
        FUtil.deleteFolder(new File("./_deleteme"));

        // Convert old config files
        new ConfigConverter(this).convert();

        BackupManager backups = new BackupManager(this);
        backups.createBackups(TotalFreedomMod.CONFIG_FILENAME, true);
        backups.createBackups(AdminList.CONFIG_FILENAME);
        backups.createBackups(PermbanList.CONFIG_FILENAME);

        config = new MainConfig(this);
        config.load();
        FPlayer.refreshConfig();

        ConfigConverter configConverter = new ConfigConverter(this);
        configConverter.convertRanksYaml();
        config.load();
        FPlayer.refreshConfig();

        // Start services
        services = new ServiceManager<>(this);
        sf = services.registerService(SavedFlags.class);
        
        // Initialize database manager first (before services that depend on it)
        dm = services.registerService(FreedomDatabase.class);
        
        wm = services.registerService(WorldManager.class);
        al = services.registerService(AdminList.class);

        configConverter.convertAdminConsoleRanks();

        // Run YAML to SQL migrations after database and admin list are ready
        runYamlMigrations();

        rm = services.registerService(RankManager.class);

        // Console sender whitelist. This first read only resolves bindings that name a legacy
        // rank, since registerService constructs RankManager without starting it and no custom
        // ranks are in memory yet. RankManager#onStart re-reads it once they are.
        csr = new ConsoleSenderRegistry(this);
        csr.load();
        // cl = services.registerService(CommandLoader.class);
        cmdl = services.registerService(CommandLoader.class);
        cb = services.registerService(CommandBlocker.class);
        // SweepScheduler must start before any guard that registers a visitor.
        sweepScheduler = services.registerService(SweepScheduler.class);
        iv = services.registerService(ItemValidator.class);
        services.registerService(ConsoleSpamFilter.class);
        sv = services.registerService(SignValidator.class);
        cps = services.registerService(CrashPacketService.class);
        eb = services.registerService(EventBlocker.class);
        bb = services.registerService(BlockBlocker.class);
        mb = services.registerService(MobBlocker.class);
        env = services.registerService(EntityNameValidator.class);
        services.registerService(EntitySizeGuard.class);
        services.registerService(SpawnerValidator.class);
        services.registerService(ProjectileGuard.class);
        services.registerService(TextDisplayGuard.class);
        services.registerService(WaypointGuard.class);
        ib = services.registerService(InteractBlocker.class);
        pb = services.registerService(PotionBlocker.class);
        lp = services.registerService(LoginProcess.class);
        nu = services.registerService(AntiNuke.class);
        adr = services.registerService(AntiDrop.class);
        as = services.registerService(AntiSpam.class);

        pl = services.registerService(PlayerList.class);
        pbl = services.registerService(PlayerBlockList.class);
        pbe = services.registerService(PlayerBlockEnforcer.class);
        jlm = services.registerService(JoinLeaveMessages.class);
        an = services.registerService(Announcer.class);
        cm = services.registerService(ChatManager.class);
        services.registerService(TextFilterService.class);
        bm = services.registerService(BanManager.class);
        pm = services.registerService(PermbanList.class);
        sl = services.registerService(StrikeList.class);
        pa = services.registerService(ProtectArea.class);
        sm = services.registerService(SpawnManager.class);
        gr = services.registerService(GameRuleHandler.class);
        services.registerService(GameModeGuard.class);
        services.registerService(me.totalfreedom.totalfreedommod.disguise.DisallowedDisguises.class);

        // Single admin utils
        cs = services.registerService(CommandSpy.class);
        ps = services.registerService(PotionSpy.class);
        ss = services.registerService(SignSpy.class);
        ca = services.registerService(Cager.class);
        fm = services.registerService(Freezer.class);
        or = services.registerService(Orbiter.class);
        mu = services.registerService(Muter.class);
        sb = services.registerService(SpectatorBlocker.class);
        fo = services.registerService(Fuckoff.class);
        ak = services.registerService(AutoKick.class);
        ae = services.registerService(AutoEject.class);

        mv = services.registerService(MovementValidator.class);
        ew = services.registerService(EntityWiper.class);
        sp = services.registerService(ServerPing.class);

        // Fun
        it = services.registerService(ItemFun.class);
        lm = services.registerService(Landminer.class);
        mp = services.registerService(MP44.class);
        jp = services.registerService(Jumppads.class);
        tr = services.registerService(Trailer.class);

        // HTTPD
        hd = services.registerService(HTTPDaemon.class);

        // SSH
        sd = services.registerService(SshDaemon.class);

        // Discord
        db = services.registerService(DiscordBridge.class);

        tl = services.registerService(TabList.class);
        services.start();

        // Start bridges
        bridges = new ServiceManager<>(this);
        cpb = bridges.registerService(CoreProtectBridge.class);
        esb = bridges.registerService(EssentialsBridge.class);
        svcb = bridges.registerService(SimpleVoiceChatBridge.class);
        ldb = bridges.registerService(LibsDisguisesBridge.class);
        web = bridges.registerService(WorldEditBridge.class);
        bridges.start();

        timer.update();
        FLog.info("Version " + pluginVersion + " enabled in " + timer.getTotal() + "ms");
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id)
    {
        if ("flatlands".equals(worldName))
        {
            String params;
            if (config != null)
            {
                params = ConfigEntry.FLATLANDS_GENERATE_PARAMS.getString();
            }
            else
            {
                saveDefaultConfig();
                params = getConfig().getString("flatlands.generate_params", "16|stone|32|dirt|1|grass_block");
            }
            return new CleanroomChunkGenerator(params);
        }
        return super.getDefaultWorldGenerator(worldName, id);
    }

    @Override
    public void onDisable()
    {
        // Stop services and bridges (check for null in case initialization failed)
        if (bridges != null)
        {
            bridges.stop();
        }
        if (services != null)
        {
            services.stop();
        }

        getServer().getScheduler().cancelTasks(this);

        FLog.info("Plugin disabled");
        PluginProvider.unbind();
    }

    public static class BuildProperties
    {

        public String author;
        public String codename;
        public String version;
        public String number;
        public String date;
        public String head;

        public void load(TotalFreedomMod plugin)
        {
            final Properties props = readResource(plugin, "build.properties");
            final Properties gitprops = readResource(plugin, "git.properties");

            author = props.getProperty("buildAuthor", "unknown");
            codename = props.getProperty("buildCodeName", "unknown");
            version = props.getProperty("buildVersion", pluginVersion);
            number = props.getProperty("buildNumber", "1");
            date = gitprops.getProperty("git.build.time", "unknown");
            head = gitprops.getProperty("git.commit.id.abbrev", "unknown");
        }

        private static Properties readResource(TotalFreedomMod plugin, String name)
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
                    FLog.warning("Build resource '" + name + "' not found in plugin jar; using defaults.");
                }
            }
            catch (Exception ex)
            {
                FLog.warning("Could not read build resource '" + name + "': " + ex.getMessage());
            }
            return properties;
        }

        public String formattedVersion()
        {
            return pluginVersion + "." + number + " (" + head + ")";
        }
    }

    /**
     * Run YAML to SQL migrations for admins, bans, and permbans.
     * This converts existing YAML files to the new SQL database format.
     */
    private void runYamlMigrations()
    {
        if (dm == null || !dm.isInitialized())
        {
            FLog.info("Database not initialized, skipping YAML migrations");
            return;
        }
        
        try
        {
            YamlMigrationService migrationService = new YamlMigrationService(this, dm);
            migrationService.runMigrations().join();
            
            // Reload admin list after migration to pick up SQL data
            if (al != null)
            {
                al.load();
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Error during YAML migrations: " + ex.getMessage());
        }
    }

}
