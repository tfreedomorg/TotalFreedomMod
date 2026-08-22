package me.totalfreedom.totalfreedommod.ssh;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.bukkit.Bukkit;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine tab completer for SSH sessions.
 */
public class SshCommandCompleter implements Completer {

    private final TotalFreedomMod plugin;

    public SshCommandCompleter(TotalFreedomMod plugin) {
        this.plugin = plugin;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();

        CompletableFuture<List<String>> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                List<String> completions = Bukkit.getCommandMap().tabComplete(Bukkit.getConsoleSender(), buffer);
                future.complete(completions);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            List<String> completions = future.get();
            if (completions != null) {
                for (String completion : completions) {
                    candidates.add(new Candidate(completion));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            FLog.warning("Error during SSH tab completion: " + e.getMessage());
        }
    }
}
