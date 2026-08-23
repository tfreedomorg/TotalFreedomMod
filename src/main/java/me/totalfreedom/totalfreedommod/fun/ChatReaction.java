package me.totalfreedom.totalfreedommod.fun;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.economy.IEcoPlayer;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ChatReaction extends FreedomService
{
    private static final String CHAR_POOL = "abcdefghijklmnopqrstuvwxyz0123456789!@#$%^*";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Sound APPEAR_SOUND = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    private static final Sound WIN_SOUND = Sound.UI_TOAST_CHALLENGE_COMPLETE;

    private boolean enabled;
    private long intervalTicks;
    private long answerDurationMillis;
    private int phraseLength;
    private int rewardAmount;

    private BukkitTask reactionTask;
    private BukkitTask barTask;
    private BossBar bossBar;
    private volatile long roundStartNanos;

    private final AtomicReference<String> activePhrase = new AtomicReference<>(null);

    public ChatReaction(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        enabled = ConfigEntry.CHATREACTION_ENABLED.getBoolean();
        final int intervalSeconds = ConfigEntry.CHATREACTION_INTERVAL.getInteger();
        final int answerSeconds = ConfigEntry.CHATREACTION_DURATION.getInteger();
        intervalTicks = intervalSeconds * 20L;
        answerDurationMillis = answerSeconds * 1000L;
        phraseLength = ConfigEntry.CHATREACTION_LENGTH.getInteger();
        rewardAmount = ConfigEntry.CHATREACTION_REWARD.getInteger();

        if (!enabled)
            return;

        reactionTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                FTask.run("ChatReaction/round", ChatReaction.this::startRound);
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    @Override
    public void onStop()
    {
        if (reactionTask != null)
        {
            FUtil.cancel(reactionTask);
            reactionTask = null;
        }
        clearRoundVisuals();
        activePhrase.set(null);
    }

    private void startRound()
    {
        if (Bukkit.getOnlinePlayers().isEmpty())
            return;

        clearRoundVisuals();

        final String phrase = generatePhrase(phraseLength);
        activePhrase.set(phrase);
        roundStartNanos = System.nanoTime();

        final Component phraseComponent = Component.text(phrase, NamedTextColor.AQUA);
        final Component announcement = MessageUtils.parse(ConfigEntry.CHATREACTION_MESSAGE.getString(),
            MessageUtils.component("phrase", phraseComponent));
        Bukkit.getServer().sendMessage(announcement);

        final Component barTitle = MessageUtils.parse(ConfigEntry.CHATREACTION_BAR_MESSAGE.getString(),
            MessageUtils.component("phrase", phraseComponent));
        bossBar = BossBar.bossBar(barTitle, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        server.getOnlinePlayers().forEach(online ->
        {
            online.showBossBar(bossBar);
            online.playSound(online.getLocation(), APPEAR_SOUND, 1f, 1f);
        });

        barTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                FTask.run("ChatReaction/bar", ChatReaction.this::updateBar);
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void updateBar()
    {
        if (bossBar == null)
            return;

        final long elapsedMillis = (System.nanoTime() - roundStartNanos) / 1_000_000L;
        final float progress = Math.max(0f, 1f - ((float) elapsedMillis / answerDurationMillis));
        bossBar.progress(progress);

        if (progress <= 0f)
        {
            final boolean roundExpired = activePhrase.getAndSet(null) != null;
            clearRoundVisuals();
            if (roundExpired)
                server.sendMessage(MessageUtils.parse(ConfigEntry.CHATREACTION_NO_WIN_MESSAGE.getString()));
        }
    }

    private void clearRoundVisuals()
    {
        if (barTask != null)
        {
            FUtil.cancel(barTask);
            barTask = null;
        }
        if (bossBar != null)
        {
            server.getOnlinePlayers().forEach(online -> online.hideBossBar(bossBar));
            bossBar = null;
        }
    }

    private static String generatePhrase(int length)
    {
        return IntStream.range(0, length)
                .mapToObj(index -> String.valueOf(CHAR_POOL.charAt(RANDOM.nextInt(CHAR_POOL.length()))))
                .collect(Collectors.joining());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncChatEvent event)
    {
        final String phrase = activePhrase.get();
        if (phrase == null)
            return;

        final String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!message.equals(phrase))
            return;

        if (activePhrase.compareAndSet(phrase, null))
        {
            final Player winner = event.getPlayer();
            final double elapsedSeconds = (System.nanoTime() - roundStartNanos) / 1_000_000_000.0;

            Bukkit.getScheduler().runTask(plugin, () ->
                    FTask.run("ChatReaction/win", () -> finishRound(winner, elapsedSeconds)));
        }
    }

    private void finishRound(Player winner, double elapsedSeconds)
    {
        clearRoundVisuals();

        server.getOnlinePlayers().forEach(online -> online.playSound(online.getLocation(), WIN_SOUND, 1f, 1f));

        if (rewardAmount > 0)
        {
            final IEcoPlayer ecoWinner = plugin.economy().getEcoPlayer(winner);
            ecoWinner.deposit(plugin.bank(), rewardAmount, 0);
        }

        final String time = String.format(Locale.ROOT, "%.2f", elapsedSeconds);
        final Component winMessage = MessageUtils.parse(ConfigEntry.CHATREACTION_WIN_MESSAGE.getString(),
                MessageUtils.component("player", Component.text(winner.getName(), NamedTextColor.AQUA)),
                MessageUtils.component("time", Component.text(time, NamedTextColor.AQUA)),
                MessageUtils.component("reward", Component.text(String.valueOf(rewardAmount), NamedTextColor.AQUA)));
        Bukkit.getServer().sendMessage(winMessage);
    }

}
