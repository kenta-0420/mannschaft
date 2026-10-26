package com.mannschaft.app.config;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.EncryptionServiceHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * 暗号化サービスのBean定義。鍵は環境変数から注入する。
 */
@Configuration
public class EncryptionConfig {

    @Bean
    public EncryptionService encryptionService(
            @Value("${mannschaft.encryption.key}") String encryptionKeyBase64,
            @Value("${mannschaft.encryption.hmac-key}") String hmacKeyBase64) {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "MANNSCHAFT_ENCRYPTION_KEY is not set. Set this environment variable before starting the application.");
        }
        if (hmacKeyBase64 == null || hmacKeyBase64.isBlank()) {
            throw new IllegalStateException(
                    "MANNSCHAFT_HMAC_KEY is not set. Set this environment variable before starting the application.");
        }
        byte[] encryptionKey = Base64.getDecoder().decode(encryptionKeyBase64);
        byte[] hmacKey = Base64.getDecoder().decode(hmacKeyBase64);
        EncryptionService service = new EncryptionService(encryptionKey, hmacKey);
        EncryptionServiceHolder.set(service);
        return service;
    }
}
