package com.mannschaft.app.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VAPID（Voluntary Application Server Identification）設定。
 * Web Push APIのVAPID認証で使用する公開鍵・秘密鍵を保持する。
 *
 * <p>application.yml の {@code mannschaft.vapid} セクションから読み込まれる。
 * 本番環境では環境変数 VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY で注入すること。
 *
 * <p>設計書: {@code docs/features/F04.3_pwa_push_notification.md}
 */
@ConfigurationProperties(prefix = "mannschaft.vapid")
@Component
@Getter
@Setter
public class VapidConfig {

    /**
     * VAPID公開鍵（Base64url形式）。
     * フロントエンドのサービスワーカーに渡してプッシュ購読を作成する際にも使用する。
     */
    private String publicKey;

    /**
     * VAPID秘密鍵（Base64url形式）。
     * サーバー側でVAPID JWTを署名するために使用する。絶対に外部に公開しないこと。
     */
    private String privateKey;
}
