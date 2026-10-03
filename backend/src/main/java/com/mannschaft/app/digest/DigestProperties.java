package com.mannschaft.app.digest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * タイムラインダイジェスト機能の設定プロパティ。
 * application.yml の mannschaft.digest 配下にバインドされる。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mannschaft.digest")
public class DigestProperties {

    /** AI プロバイダー（claude / openai） */
    private String aiProvider = "claude";

    /** AI モデル名 */
    private String aiModel = "claude-haiku-4-5";

    /** AI 温度パラメータ */
    private double aiTemperature = 0.3;

    /** AI 最大出力トークン数 */
    private int aiMaxTokens = 2000;

    /** スコープあたり月次生成上限 */
    private int monthlyLimitPerScope = 30;

    /** 月次 AI コストアラート閾値（円） */
    private int costAlertThresholdJpy = 1000;

    /** Claude API 呼び出しのタイムアウト（ミリ秒、既定 5 秒）。 */
    private int timeoutMs = 5000;

    /** Claude API 呼び出しのリトライ最大試行回数（既定 3）。 */
    private int retryMaxAttempts = 3;

    /** リトライ初回バックオフ遅延（ミリ秒、指数バックオフの基点。既定 200ms）。 */
    private int retryBackoffDelayMs = 200;

    /** config 未作成スコープでのフォールバック設定 */
    private Defaults defaults = new Defaults();

    /**
     * デフォルト設定。
     */
    @Getter
    @Setter
    public static class Defaults {

        /** 最低投稿数 */
        private int minPostsThreshold = 3;

        /** 1回あたりの最大投稿数 */
        private int maxPostsPerDigest = 100;

        /** 投稿 content の最大文字数 */
        private int contentMaxChars = 500;
    }
}
