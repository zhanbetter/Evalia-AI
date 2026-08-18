package com.eval.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 等敏感信息的 AES-256-GCM 加解密工具
 *
 * 设计要点：
 * - 使用 AES/GCM/NoPadding，密文 = IV(12字节) + 密文
 * - 密钥从配置读取（Base64 编码的 32 字节），未配置时使用默认开发密钥
 * - 兼容旧数据：decrypt 失败（说明是历史明文存储）时原样返回，保证不破坏已有数据
 */
@Slf4j
@Component
public class EncryptUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String PREFIX = "enc:";

    /** 默认开发密钥（仅用于开发环境，生产必须通过 EVAL_ENCRYPT_KEY 配置） */
    private static final String DEFAULT_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private SecretKeySpec secretKey;

    public EncryptUtil(@Value("${eval.encrypt.secret-key:}") String base64Key) {
        try {
            byte[] keyBytes;
            if (StringUtils.hasText(base64Key)) {
                keyBytes = Base64.getDecoder().decode(base64Key);
            } else {
                keyBytes = Base64.getDecoder().decode(DEFAULT_KEY_B64);
                log.warn("未配置 eval.encrypt.secret-key，使用默认开发密钥！生产环境请务必配置");
            }
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("AES密钥必须是32字节(256位)，当前=" + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            log.error("初始化加密密钥失败", e);
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    @PostConstruct
    public void init() {
        // 验证加解密可用
        try {
            String test = encrypt("__test__");
            decrypt(test);
        } catch (Exception e) {
            log.error("加密组件自检失败", e);
        }
    }

    /**
     * 加密文本，返回 "enc:" 前缀 + Base64(IV + 密文)
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = ByteBuffer.allocate(IV_LENGTH + encrypted.length)
                    .put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密文本。兼容旧数据：无前缀或解密失败时原样返回。
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return cipherText;
        // 非加密格式（旧数据明文），原样返回
        if (!cipherText.startsWith(PREFIX)) {
            return cipherText;
        }
        try {
            String b64 = cipherText.substring(PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(b64);
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("解密失败(可能为历史明文数据)，原样返回");
            return cipherText;
        }
    }

    /**
     * 脱敏展示：保留前4位，其余用 * 代替
     */
    public String mask(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return "";
        String plain = decrypt(apiKey);
        if (plain.length() <= 8) return "****";
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }
}
