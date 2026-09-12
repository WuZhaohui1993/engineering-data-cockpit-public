package com.ruoyi.system.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 出站目标校验。校验在解析 DNS 后执行，并在每次请求前调用；HTTP 客户端同时关闭
 * 自动重定向，避免把受控来源变成任意网络代理。
 */
public final class DashboardIntegrationNetwork
{
    private DashboardIntegrationNetwork()
    {
    }

    public record Target(URI uri, List<InetAddress> addresses)
    {
    }

    public static Target validate(String rawUrl, String profile, Collection<String> allowedHosts,
            Collection<String> allowedCidrs, Collection<Integer> allowedPorts,
            boolean allowPlainHttp, boolean allowLocalDevelopmentTargets)
    {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("来源地址不能为空");
        URI uri;
        try
        {
            uri = URI.create(rawUrl.trim());
        }
        catch (IllegalArgumentException ex)
        {
            throw new IllegalArgumentException("来源地址不合法", ex);
        }
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        String normalizedProfile = normalizeProfile(profile);
        boolean publicHttp = "PUBLIC_HTTP".equals(normalizedProfile);
        boolean localTargetsAllowed = allowLocalDevelopmentTargets && !publicHttp;
        if (!"http".equals(scheme) && !"https".equals(scheme))
            throw new IllegalArgumentException("来源只允许 HTTP 或 HTTPS");
        if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("来源主机或 URI 属性不合法");
        if ("PUBLIC_HTTPS".equals(normalizedProfile) && !"https".equals(scheme))
            throw new IllegalArgumentException("PUBLIC_HTTPS 来源必须使用 HTTPS");
        if ("http".equals(scheme) && !publicHttp && !allowPlainHttp)
            throw new IllegalArgumentException("生产来源禁止明文 HTTP");

        if (publicHttp && (allowedHosts == null || allowedHosts.isEmpty()))
            throw new IllegalArgumentException("PUBLIC_HTTP 必须显式配置来源主机白名单");
        if (!matchesHost(host, allowedHosts) && (publicHttp || !matchesLiteralAddressCidr(host, allowedCidrs)))
            throw new IllegalArgumentException("来源主机不在白名单");

        int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("来源端口不合法");
        if (publicHttp && (allowedPorts == null || allowedPorts.isEmpty()))
            throw new IllegalArgumentException("PUBLIC_HTTP 必须显式配置来源端口白名单");
        if (allowedPorts != null && !allowedPorts.isEmpty() && !allowedPorts.contains(port))
            throw new IllegalArgumentException("来源端口不在白名单");

        List<InetAddress> addresses = resolve(host);
        boolean privateLink = "PRIVATE_LINK".equals(normalizedProfile);
        if (privateLink && !allowLocalDevelopmentTargets
                && (allowedCidrs == null || allowedCidrs.isEmpty()))
            throw new IllegalArgumentException("PRIVATE_LINK 必须配置批准的 CIDR");
        for (InetAddress address : addresses)
        {
            if (isMetadataOrUnusableAddress(address))
                throw new IllegalArgumentException("来源解析地址属于禁止访问的元数据或不可用网段");
            boolean approvedCidr = matchesAnyCidr(address, allowedCidrs);
            if (privateLink)
            {
                if (!allowLocalDevelopmentTargets && !approvedCidr)
                    throw new IllegalArgumentException("PRIVATE_LINK 来源解析地址不在批准网段");
                if (!allowLocalDevelopmentTargets && isPrivateLinkForbiddenAddress(address))
                    throw new IllegalArgumentException("PRIVATE_LINK 仍禁止回环、链路本地或组播地址");
            }
            else if (isForbiddenAddress(address) && !localTargetsAllowed)
                throw new IllegalArgumentException("公网来源解析地址属于禁止访问的本地或保留网段");
            if (!privateLink && !localTargetsAllowed
                    && allowedCidrs != null && !allowedCidrs.isEmpty() && !approvedCidr)
                throw new IllegalArgumentException("来源解析地址不在批准网段");
        }
        return new Target(uri, Collections.unmodifiableList(addresses));
    }

    public static String normalizeProfile(String profile)
    {
        String normalized = profile == null || profile.isBlank()
                ? "PUBLIC_HTTPS" : profile.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PUBLIC_HTTPS", "PUBLIC_HTTP", "PRIVATE_LINK").contains(normalized))
            throw new IllegalArgumentException("HTTP 数据源网络策略不支持");
        return normalized;
    }

    public static List<InetAddress> resolve(String host)
    {
        try
        {
            return List.of(InetAddress.getAllByName(host));
        }
        catch (UnknownHostException ex)
        {
            throw new IllegalArgumentException("来源主机无法解析", ex);
        }
    }

    public static boolean matchesHost(String host, Collection<String> allowedHosts)
    {
        if (allowedHosts == null) return false;
        String normalized = lower(host);
        for (String candidate : allowedHosts)
        {
            if (candidate == null || candidate.isBlank()) continue;
            String value = lower(candidate.trim());
            if (value.equals(normalized)) return true;
            if (value.startsWith("*.") && normalized.endsWith(value.substring(1))
                    && normalized.length() > value.length() - 1)
                return true;
        }
        return false;
    }

    public static boolean isForbiddenAddress(InetAddress address)
    {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4)
        {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            int d = bytes[3] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 169 && b == 254) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 192 && b == 0 && c == 0) return true;
            if (a == 192 && b == 0 && c == 2) return true;
            if (a == 198 && (b == 18 || b == 19)) return true;
            if (a == 198 && b == 51 && c == 100) return true;
            if (a == 203 && b == 0 && c == 113) return true;
            if (a == 169 && b == 254 && c == 169 && d == 254) return true;
            if (a == 100 && b == 100 && c == 100 && d == 200) return true;
        }
        else if (bytes.length == 16)
        {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80)) return true;
            if (isIpv4Mapped(address)) return isForbiddenAddress(mappedIpv4(address));
            if (first == 0 && second == 0) return true;
        }
        return false;
    }

    /**
     * 即使启用了 PRIVATE_LINK 或本地开发开关也不能访问的目标。云元数据、未指定地址
     * 和组播地址不属于任何业务专网；本地开发开关只用于显式登记的回环/局域网测试服务。
     */
    private static boolean isMetadataOrUnusableAddress(InetAddress address)
    {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4)
        {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            int d = bytes[3] & 0xff;
            return a == 0 || a >= 224 || (a == 169 && b == 254 && c == 169 && d == 254)
                    || (a == 100 && b == 100 && c == 100 && d == 200);
        }
        if (bytes.length == 16 && isIpv4Mapped(address))
            return isMetadataOrUnusableAddress(mappedIpv4(address));
        return false;
    }

    private static boolean isPrivateLinkForbiddenAddress(InetAddress address)
    {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return true;
        if (isIpv4Mapped(address)) return isPrivateLinkForbiddenAddress(mappedIpv4(address));
        return false;
    }

    private static boolean isIpv4Mapped(InetAddress address)
    {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static InetAddress mappedIpv4(InetAddress address)
    {
        byte[] source = address.getAddress();
        byte[] target = new byte[4];
        System.arraycopy(source, 12, target, 0, 4);
        try { return InetAddress.getByAddress(target); }
        catch (UnknownHostException ex) { throw new IllegalArgumentException("IPv4 映射地址不合法", ex); }
    }

    private static boolean matchesLiteralAddressCidr(String host, Collection<String> cidrs)
    {
        if (cidrs == null || cidrs.isEmpty()) return false;
        if (!isIpLiteral(host)) return false;
        try
        {
            return matchesAnyCidr(InetAddress.getByName(host), cidrs);
        }
        catch (UnknownHostException ex) { return false; }
    }

    private static boolean isIpLiteral(String host)
    {
        if (host == null || host.isBlank()) return false;
        if (host.indexOf(':') >= 0) return host.matches("[0-9A-Fa-f:.]+");
        return host.matches("[0-9.]+");
    }

    private static boolean matchesAnyCidr(InetAddress address, Collection<String> cidrs)
    {
        if (address == null || cidrs == null) return false;
        for (String cidr : cidrs)
        {
            if (cidr == null || cidr.isBlank()) continue;
            String[] parts = cidr.trim().split("/", 2);
            try
            {
                InetAddress network = InetAddress.getByName(parts[0]);
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.getAddress().length * 8;
                byte[] actual = address.getAddress();
                byte[] expected = network.getAddress();
                if (actual.length != expected.length || prefix < 0 || prefix > actual.length * 8) continue;
                int fullBytes = prefix / 8;
                int remaining = prefix % 8;
                boolean same = true;
                for (int i = 0; i < fullBytes; i++) if (actual[i] != expected[i]) { same = false; break; }
                if (same && remaining > 0)
                {
                    int mask = 0xff << (8 - remaining);
                    same = (actual[fullBytes] & mask) == (expected[fullBytes] & mask);
                }
                if (same) return true;
            }
            catch (RuntimeException | UnknownHostException ignored) { }
        }
        return false;
    }

    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
