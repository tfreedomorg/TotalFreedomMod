package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.net.InetAddress;

import com.google.common.net.InetAddresses;

public class InetAddressResolver implements AbstractArgumentResolver<InetAddress>
{
    @Override
    public String name()
    {
        return "IP";
    }

    @Override
    public InetAddress resolve(String arg, String strategy)
    {
        if (!InetAddresses.isInetAddress(arg))
        {
            throw new ArgumentResolutionException("Invalid IP address: " + arg);
        }

        return InetAddress.ofLiteral(arg);
    }
}
