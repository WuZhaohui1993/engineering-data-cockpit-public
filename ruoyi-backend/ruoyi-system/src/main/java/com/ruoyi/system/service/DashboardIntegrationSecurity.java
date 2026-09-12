package com.ruoyi.system.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 第三方接入共用的纯计算安全工具。
 *
 * 该类不记录请求内容、认证头或密钥，便于在服务端认证和单元回归中复用。
 */
public final class DashboardIntegrationSecurity
{
    private static final Pattern SAFE_EXTERNAL_VALUE = Pattern.compile("[A-Za-z0-9._:@/+\\-]{1,256}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DashboardIntegrationSecurity()
    {
    }

    public static boolean constantTimeEquals(String left, String right)
    {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value));
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    public static String hmacSha256Base64(String secret, String canonical)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((canonical == null ? "" : canonical).getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("HMAC-SHA256 不可用", ex);
        }
    }

    public static String randomToken(int bytes)
    {
        int length = Math.max(16, Math.min(bytes, 128));
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static boolean isSafeExternalValue(String value)
    {
        return value != null && SAFE_EXTERNAL_VALUE.matcher(value).matches()
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    public static String normalizeExternalValue(String value, String name)
    {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 256 || !isSafeExternalValue(normalized))
            throw new IllegalArgumentException(name + " 不合法");
        return normalized;
    }

    public static long parseTimestampMillis(String value, String unit)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("时间戳不能为空");
        String normalizedUnit = unit == null ? "MILLISECONDS" : unit.toUpperCase(Locale.ROOT);
        try
        {
            if ("RFC3339".equals(normalizedUnit) || "ISO8601".equals(normalizedUnit))
                return Instant.parse(value).toEpochMilli();
            long numeric = Long.parseLong(value);
            if ("SECONDS".equals(normalizedUnit)) return Math.multiplyExact(numeric, 1000L);
            if (!"MILLISECONDS".equals(normalizedUnit)) throw new IllegalArgumentException("时间戳单位不支持");
            return numeric;
        }
        catch (RuntimeException ex)
        {
            throw new IllegalArgumentException("时间戳格式不合法", ex);
        }
    }

    public static boolean withinClockSkew(long timestampMillis, long nowMillis, long skewSeconds)
    {
        long allowed = Math.max(1L, Math.min(skewSeconds, 3600L)) * 1000L;
        long difference;
        try
        {
            difference = Math.abs(Math.subtractExact(nowMillis, timestampMillis));
        }
        catch (ArithmeticException ex)
        {
            return false;
        }
        return difference <= allowed;
    }
}
