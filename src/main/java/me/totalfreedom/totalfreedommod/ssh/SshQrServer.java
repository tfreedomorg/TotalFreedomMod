package me.totalfreedom.totalfreedommod.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A single, shared HTTP daemon that serves one-time TOTP QR setup pages.
 * Requests are held in a bounded FIFO queue keyed by token; a request is popped and
 * discarded as soon as its page is retrieved. The daemon shuts itself down once the
 * queue drains, or after 5 minutes pass without a new request being queued.
 */
public final class SshQrServer
{
    private static final int MAX_PENDING = 20;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Object LOCK = new Object();

    private static SshQrServer instance;

    private final HttpServer server;
    private final ScheduledExecutorService scheduler;
    private final LinkedHashMap<String, PendingRequest> pending = new LinkedHashMap<>();
    private ScheduledFuture<?> idleCheck;

    private record PendingRequest(String otpUri, String label, Instant createdAt) {}

    private SshQrServer(int port) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        server.createContext("/qr", this::handle);
        server.start();
        FLog.info("[SSH] QR setup server started on port " + port + ".");
    }

    public static void enqueue(String otpUri, String label, String token)
    {
        synchronized (LOCK)
        {
            try
            {
                if (instance == null)
                {
                    instance = new SshQrServer(ConfigEntry.SSH_QR_PORT.getInteger());
                }
                instance.add(token, otpUri, label);
            }
            catch (IOException e)
            {
                FLog.warning("[SSH] Failed to start QR server: " + e.getMessage());
            }
        }
    }

    private void add(String token, String otpUri, String label)
    {
        synchronized (LOCK)
        {
            if (pending.size() >= MAX_PENDING)
            {
                Iterator<String> oldest = pending.keySet().iterator();
                if (oldest.hasNext())
                {
                    oldest.next();
                    oldest.remove();
                }
            }
            pending.put(token, new PendingRequest(otpUri, label, Instant.now()));

            if (idleCheck != null)
            {
                idleCheck.cancel(false);
            }
            idleCheck = scheduler.schedule(this::checkIdle, IDLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void checkIdle()
    {
        synchronized (LOCK)
        {
            if (instance != this)
            {
                return;
            }

            if (pending.isEmpty())
            {
                shutdown();
                return;
            }

            // I really REALLY wanted to use chained lambdas but I keep getting null checks from my IDE SO I GUESS THIS IS WHAT WE'RE DOING
            Instant newest = Instant.EPOCH;
            for (PendingRequest request : pending.values())
            {
                if (request.createdAt().isAfter(newest))
                {
                    newest = request.createdAt();
                }
            }
            if (Duration.between(newest, Instant.now()).compareTo(IDLE_TIMEOUT) >= 0)
            {
                FLog.info("[SSH] QR setup server timed out after 5 minutes of inactivity.");
                shutdown();
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        String token = extractToken(exchange.getRequestURI().getQuery());
        PendingRequest request;
        boolean nowEmpty;

        synchronized (LOCK)
        {
            request = token != null ? pending.remove(token) : null;
            nowEmpty = pending.isEmpty();
        }

        if (request == null)
        {
            sendResponse(exchange, 410, loadTemplate("/ssh/qr-gone.html"));
        }
        else
        {
            sendResponse(exchange, 200, buildHtml(request.otpUri(), request.label()));
        }

        if (nowEmpty)
        {
            synchronized (LOCK)
            {
                if (instance == this && pending.isEmpty())
                {
                    shutdown();
                }
            }
        }
    }

    // Must be called while holding LOCK.
    private void shutdown()
    {
        if (instance != this)
        {
            return;
        }
        instance = null;
        server.stop(0);
        if (!scheduler.isShutdown())
        {
            scheduler.shutdown();
        }
        FLog.info("[SSH] QR setup server stopped.");
    }

    private static String extractToken(String query)
    {
        if (query == null)
        {
            return null;
        }
        for (String param : query.split("&"))
        {
            int eq = param.indexOf('=');
            if (eq > 0 && "t".equals(param.substring(0, eq)))
            {
                return param.substring(eq + 1);
            }
        }
        return null;
    }

    private static void sendResponse(HttpExchange exchange, int status, String html) throws IOException
    {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String buildHtml(String otpUri, String label)
    {
        String escapedForJs = otpUri.replace("\\", "\\\\").replace("\"", "\\\"");
        return loadTemplate("/ssh/qr-setup.html")
                .replace("${LABEL}", escapeHtml(label))
                .replace("${OTP_URI_JS}", escapedForJs)
                .replace("${OTP_URI}", otpUri);
    }

    private static String escapeHtml(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String loadTemplate(String resourcePath)
    {
        try (InputStream in = SshQrServer.class.getResourceAsStream(resourcePath))
        {
            if (in == null)
            {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            FLog.severe("[SSH] Failed to load QR template '" + resourcePath + "': " + e.getMessage());
            return "<html><body><p>Internal error.</p></body></html>";
        }
    }
}
