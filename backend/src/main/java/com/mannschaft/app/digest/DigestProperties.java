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
