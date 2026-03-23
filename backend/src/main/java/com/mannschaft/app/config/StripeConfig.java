package com.mannschaft.app.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe API キー設定。起動時に Stripe.apiKey を初期化する。
 */
@Slf4j
@Configuration
public class StripeConfig {

    @Value("${mannschaft.stripe.secret-key:}")
    private String secretKey;

    @PostConstruct
    public void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe API キー設定完了");
        } else {
            log.warn("Stripe API キーが未設定です。決済機能は動作しません。");
        }
    }
}
