package com.mannschaft.app.jobmatching.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * F13.1 Phase 13.1.2 — QR チェックイン／アウト トークン署名設定。
 *
 * <p>application.yml の {@code mannschaft.jobs.qr} 配下にバインドされる。</p>
 *
 * <p>設計書 §2.3.1 / §10.10 を参照。</p>
 *
 * <ul>
 *   <li>{@code ttlSeconds}: デフォルト TTL（60 秒）</li>
 *   <li>{@code ttlSecondsMax}: TTL 上限（5 分 = 300 秒）。Requester が延長 TTL を指定しても超過させない</li>
 *   <li>{@code rotationAdvanceSeconds}: Requester 画面が次トークンを先行取得するリードタイム（5 秒）</li>
 *   <li>{@code shortCodeLength}: 手動入力フォールバック用短コード長（6 文字）</li>
 *   <li>{@code signingKeys}: HMAC-SHA256 署名鍵リスト。{@code kid} で識別、{@code active=true} の最新鍵で新規発行、
 *       検証時は全鍵から {@code kid} 一致で解決（鍵ローテーション対応）</li>
 * </ul>
 *
 * <p>本番では {@code JOB_QR_SIGNING_SECRET} 環境変数未設定時に {@link NotBlank} バリデーションで
 * Spring Boot 起動失敗となる。</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "mannschaft.jobs.qr")
public class QrSigningProperties {

    /** デフォルト TTL（秒）。Requester が明示指定しない場合に使用。 */
    @Min(1)
    private int ttlSeconds = 60;

    /** TTL 上限（秒）。Requester が TTL 延長を指定しても超過させない。 */
    @Min(1)
    private int ttlSecondsMax = 300;

    /** 次トークンを先行取得するリードタイム（秒）。Requester 画面の自動ローテーション用。 */
    @Min(0)
    private int rotationAdvanceSeconds = 5;

    /** 手動入力フォールバック用短コード長（文字）。 */
    @Min(4)
    private int shortCodeLength = 6;

    /**
     * Geolocation 乖離判定の閾値（メートル）。業務場所と端末位置の Haversine 距離が
     * この値を超えた場合に {@code geo_anomaly=TRUE} を立てる（設計書 §10.10）。
     * デフォルト 500 m。自動拒否はせず Requester へアラート通知するのみ。
     */
    @Min(1)
    private int anomalyDistanceMeters = 500;

    /** HMAC-SHA256 署名鍵リスト（必須、最低 1 件）。 */
    @NotEmpty
    @Valid
    private List<SigningKey> signingKeys;

    /**
     * HMAC 署名鍵エントリ。
     *
     * <p>{@code kid} で鍵を識別し、{@code active=true} の鍵で新規発行する。
     * 検証時は全鍵から {@code kid} 一致で解決する（鍵ローテーション中も旧鍵で検証可能）。</p>
     */
    @Getter
    @Setter
    public static class SigningKey {

        /** 鍵 ID（JWT ヘッダの {@code kid} に埋め込む）。 */
        @NotBlank
        private String kid;

        /** HMAC-SHA256 用 secret（UTF-8 バイト列で 32 bytes 以上必須）。 */
        @NotBlank
        private String secret;

        /** アクティブフラグ。新規発行に使う鍵は {@code true}、旧鍵は {@code false} で検証のみ。 */
        @NotNull
        private Boolean active = Boolean.TRUE;
    }
}
