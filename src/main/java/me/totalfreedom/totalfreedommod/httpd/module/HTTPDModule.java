package me.totalfreedom.totalfreedommod.httpd.module;

import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.httpd.HTTPDPageBuilder;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD.HTTPSession;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD.Method;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD.Response;
import me.totalfreedom.totalfreedommod.util.FLog;

import com.google.common.html.HtmlEscapers;

public abstract class HTTPDModule extends PluginComponent<TotalFreedomMod>
{

    protected final String uri;
    protected final Method method;
    protected final Map<String, String> headers;
    protected final Map<String, String> params;
    protected final Socket socket;
    protected final HTTPSession session;

    public HTTPDModule(TotalFreedomMod plugin, HTTPSession session)
    {
        super(plugin);
        this.uri = session.getUri();
        this.method = session.getMethod();
        this.headers = session.getHeaders();
        this.params = session.getParms();
        this.socket = session.getSocket();
        this.session = session;
    }

    public String getBody()
    {
        return null;
    }

    public String getTitle()
    {
        return null;
    }

    public String getStyle()
    {
        return null;
    }

    public String getScript()
    {
        return null;
    }

    public Response getResponse()
    {
        return new HTTPDPageBuilder(getBody(), getTitle(), getStyle(), getScript()).getResponse();
    }

    protected final Map<String, String> getFiles()
    {
        Map<String, String> files = new HashMap<>();

        try
        {
            session.parseBody(files);
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }

        return files;
    }

    protected final String getClientAddress()
    {
        final String socketAddress = socket.getInetAddress().getHostAddress();

        if (!isTrustedProxy(socketAddress))
        {
            return socketAddress;
        }

        final String realIp = headers.get("x-real-ip");
        if (realIp != null && !realIp.trim().isEmpty())
        {
            return realIp.trim();
        }

        final String forwardedFor = headers.get("x-forwarded-for");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty())
        {
            final String[] hops = forwardedFor.split(",");
            final String clientHop = hops[hops.length - 1].trim();
            if (!clientHop.isEmpty())
            {
                return clientHop;
            }
        }

        return socketAddress;
    }

    private boolean isTrustedProxy(String address)
    {
        if (address == null)
        {
            return false;
        }
        final List<String> trusted = ConfigEntry.HTTPD_TRUSTED_PROXIES.getStringList();
        return trusted != null && trusted.contains(address);
    }

    public static String escapeHtml(String input)
    {
        return HtmlEscapers.htmlEscaper().escape(input);
    }
}
