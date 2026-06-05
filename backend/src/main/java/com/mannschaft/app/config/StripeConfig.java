package com.mannschaft.app.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe API キー設定。起動時に Stripe.apiKey を初期化する。
 *
 * <p><b>⚠️ SDK バージョン依存（F08.9 P5 継続課金・必読）:</b><br>
 * 本プロジェクトは <b>stripe-java 28.x 固定</b>（build.gradle.kts）。29.x(basil)以降は
 * {@code invoice.application_fee_amount} / {@code transfer_data} / {@code charge} 等が
 * 新 Invoice Payments 構造へ移行して invoice オブジェクトから消え、
 * P5 継続課金の「{@code invoice.created} の draft 窓で {@code application_fee_amount} を固定上書きする
 * 手数料機構」が<b>黙殺で壊れる</b>（HTTP 200 で無視される）。<br>
 * PoC 2026-06-05 実証では Stripe API バージョン <b>{@code 2025-02-24.acacia}</b> で機構が成立し、
 * basil 系（{@code 2025-03-31} 以降）で黙殺を確認した。stripe-java を 29.x 以降へ上げる際は、
 * P5 の invoice 上書き機構の再設計（新 Invoice Payments 構造への移行）が必須。<br>
 * 詳細: docs/features/F08.9_membership_billing_paywall/README §11-3 /
 * scripts/poc/README_f089_p5_poc.md §0。</p>
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
