package com.mannschaft.app.common.pdf;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 内部 PDF 署名トークン用の設定プロパティ（F12.1 §5.14 / F09.15 §9.4）。
 *
 * <p>v1 は RFC3161 TSA を使わない簡易方式: SHA-256 + HMAC-SHA256（サーバー秘密鍵）。
 * 収益化後の v2 で本格 TSA に置換予定。
 *
 * <p>環境変数 {@code MANNSCHAFT_INTERNAL_SIGNING_KEY} または application.yml の
 * {@code mannschaft.security.internal-signing-key} で設定する。
 * 鍵未設定時は起動失敗（fail-fast）させるため、{@link com.mannschaft.app.config.InternalPdfSigningConfig}
 * で検証を行う。
 */
@Validated
@ConfigurationProperties(prefix = "mannschaft.security")
public record InternalPdfSigningProperties(String internalSigningKey) {
}
