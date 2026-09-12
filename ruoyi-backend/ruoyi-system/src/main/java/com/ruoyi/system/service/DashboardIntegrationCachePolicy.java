package com.ruoyi.system.service;

import com.ruoyi.common.exception.ServiceException;
import java.math.BigInteger;
import java.util.Map;

/** 与可视缓存配置共用的整数契约；关闭缓存必须三个窗口都为零。 */
public final class DashboardIntegrationCachePolicy
{
    private DashboardIntegrationCachePolicy() { }
    public static void validate(Map<String, Object> config)
    {
        seconds(config, "cacheSeconds", "缓存有效秒数", 3600);
        seconds(config, "sharedFetchSeconds", "共享缓存秒数", 3600);
        seconds(config, "staleIfErrorSeconds", "故障沿用缓存秒数", 86400);
    }
    private static void seconds(Map<String, Object> config, String key, String label, int maximum)
    {
        if (!config.containsKey(key)) return;
        Object value = config.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger))
            throw new ServiceException(label + "必须是 0 至 " + maximum + " 的整数");
        BigInteger number = new BigInteger(value.toString());
        if (number.signum() < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0)
            throw new ServiceException(label + "必须是 0 至 " + maximum + " 的整数");
    }
}
