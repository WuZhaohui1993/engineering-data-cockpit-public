package com.ruoyi.system.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.core.env.Environment;

/** 服务端凭证和入站原始报文的 AES-GCM 加解密。 */
public final class DashboardIntegrationCrypto
{
    private static final SecureRandom RANDOM = new SecureRandom();

    private DashboardIntegrationCrypto()
    {
    }

    public static String encrypt(String value, Environment environment)
    {
        if (value == null || value.isBlank()) return "";
        try
        {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(environment), "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        }
        catch (Exception ex)
        {
            throw new ServiceException("接入凭证加密失败");
        }
    }

    public static String decrypt(String value, Environment environment)
    {
        if (value == null || value.isBlank()) return "";
        try
        {
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length <= 12) throw new IllegalArgumentException("短密文");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(environment), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(payload, 0, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(payload, 12, payload.length)), StandardCharsets.UTF_8);
        }
        catch (Exception ex)
        {
            throw new ServiceException("接入凭证无法解密");
        }
    }

    private static byte[] key(Environment environment)
    {
        String configured = environment == null ? "" : environment.getProperty("dashboard.datasource.encryption-key", "");
        if (configured == null || configured.isBlank()) throw new ServiceException("未配置接入凭证加密密钥");
        try
        {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length >= 16) return Arrays.copyOf(decoded, 32);
        }
        catch (IllegalArgumentException ignored) { }
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ex)
        {
            throw new ServiceException("接入凭证加密密钥不可用");
        }
    }
}
