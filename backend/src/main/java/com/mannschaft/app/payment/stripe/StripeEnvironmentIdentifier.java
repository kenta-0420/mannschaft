package com.mannschaft.app.payment.stripe;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 価格改定（price-revisions）決定9・AC-79: Stripe の test/live Price 分離のための環境識別子。
 *
 * <p>新しい設定項目は増やさず、既存の唯一の正準である {@code mannschaft.stripe.secret-key}
 * （{@link com.mannschaft.app.config.StripeConfig} が {@code Stripe.apiKey} 初期化に使うのと
 * 同じ値）のプレフィックスから導出する。Stripe の鍵命名規約は
 * {@code sk_test_}/{@code sk_live_}（標準鍵）と {@code rk_test_}/{@code rk_live_}
 * （restricted key）の4種であり、本クラスはこの4つの前方一致だけで判定する
 * （"_test_"/"_live_" を含むかでの緩い判定はしない）。</p>
 *
 * <p><b>秘密の非露出:</b> 鍵の値そのもの・その一部（末尾数桁等）は一切保持・ログ出力しない。
 * 起動時に一度だけ判定し、{@code "test"}/{@code "live"}/{@code "unknown"} の3値のいずれかの
 * 結果文字列だけをフィールドに保持する。</p>
 */
@Slf4j
@Component
public class StripeEnvironmentIdentifier {

    private static final String TEST_MODE = "test";
    private static final String LIVE_MODE = "live";
    private static final String UNKNOWN_MODE = "unknown";

    @Value("${mannschaft.stripe.secret-key:}")
    private String secretKeyForStartupClassificationOnly;

    /** 判定結果のみを保持する（鍵の値そのものは保持しない）。 */
    private String environmentId = UNKNOWN_MODE;

    @PostConstruct
    void classify() {
        String key = secretKeyForStartupClassificationOnly;
        if (key != null && (key.startsWith("sk_test_") || key.startsWith("rk_test_"))) {
            environmentId = TEST_MODE;
        } else if (key != null && (key.startsWith("sk_live_") || key.startsWith("rk_live_"))) {
            environmentId = LIVE_MODE;
        } else {
            environmentId = UNKNOWN_MODE;
        }
        // 鍵の値そのもの・一部は絶対にログへ出さない。判定結果のみ。
        log.info("Stripe環境識別子を判定: environmentId={}", environmentId);
        // 判定に使った生の鍵はもう不要。フィールドに保持し続けない（秘密の残留を避ける）。
        secretKeyForStartupClassificationOnly = null;
    }

    /** 判定済みの環境識別子（{@code test}/{@code live}/{@code unknown}）。鍵そのものは含まない。 */
    public String environmentId() {
        return environmentId;
    }

    /**
     * テスト専用ファクトリ: Spring起動（{@code @PostConstruct}）を経ずに、環境識別子を
     * 明示的に指定したインスタンスを作る。単体テストで {@code new StripeEnvironmentIdentifier()}
     * の既定値（{@code unknown}）以外の値（{@code test}/{@code live}）を固定したい場合に使う。
     */
    public static StripeEnvironmentIdentifier forTesting(String environmentId) {
        StripeEnvironmentIdentifier instance = new StripeEnvironmentIdentifier();
        instance.environmentId = environmentId;
        return instance;
    }
}
