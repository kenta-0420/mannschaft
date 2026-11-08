package com.mannschaft.app.admin.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BatchNotificationTransactionIT} が使う {@code @BatchEndpoint} 付きの検体（本番のバッチの付与形を模す）。
 *
 * <p>検体のメソッドに付く {@code @Transactional} は「テストの TX」ではなく「エンドポイント自身の TX」であり、
 * 検体を呼ぶたびに本物の TX インタセプタ越しに commit / rollback する。テストクラスは TX を持たないため、
 * AFTER_COMMIT リスナーは実際に発火し、通知行の有無の判定は空振りしない。
 * 検体をテストクラスから独立したファイルに置くのは、テストクラスのソースに {@code @Transactional} を
 * 持ち込まないためである（番人 TransactionalTestNotificationObservationGuardTest はソース単位で
 * トランザクショナル注釈を判定する）。</p>
 */
class BatchNotificationProbe {
    // 検体は CGLIB プロキシ越しに使うため、インスタンスフィールドはプロキシ側から見えない（null）。static に置く。
    static final AtomicInteger BODY_CALLS = new AtomicInteger();
    private final ApplicationEventPublisher publisher;
    private final JdbcTemplate jdbcTemplate;

    BatchNotificationProbe(ApplicationEventPublisher publisher, JdbcTemplate jdbcTemplate) {
        this.publisher = publisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 業務行を書き込む TX 付きの検体（共通 AC-B/B2: 通知の失敗に業務更新が巻き込まれないこと）。 */
    @BatchEndpoint(name = "g7b-it-biz-write")
    @Transactional
    public int writeBusinessRow(Long userId, String marker) {
        BODY_CALLS.incrementAndGet();
        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", marker, userId);
        return 1;
    }

    @BatchEndpoint(name = "g7b-it-no-tx")
    public int noTx() {
        BODY_CALLS.incrementAndGet();
        return 5;
    }

    @BatchEndpoint(name = "g7b-it-tx-ok")
    @Transactional
    public int txOk() {
        BODY_CALLS.incrementAndGet();
        return 3;
    }

    @BatchEndpoint(name = "g7b-it-tx-throws")
    @Transactional
    public int txThrows() {
        BODY_CALLS.incrementAndGet();
        throw new IllegalStateException("本体の失敗（検証用）");
    }

    @BatchEndpoint(name = "g7b-it-commit-fails")
    @Transactional
    public int commitFails() {
        BODY_CALLS.incrementAndGet();
        publisher.publishEvent(new BatchNotificationCommitProbeEvent("commit-fails"));
        return 9;
    }

    @BatchEndpoint(name = "g7b-it-log-fails")
    public int logFails() {
        BODY_CALLS.incrementAndGet();
        return 1;
    }

    @BatchEndpoint(name = "g7b-it-body-throws-no-tx")
    public int bodyThrowsNoTx() {
        BODY_CALLS.incrementAndGet();
        throw new IllegalStateException("本体の失敗・TXなし（検証用）");
    }

    @BatchEndpoint(name = "g7b-it-per-admin")
    public int perAdmin() {
        BODY_CALLS.incrementAndGet();
        return 1;
    }

    /** 未シードのゲートキーは isEnabled=false（フェイルクローズ）なので常に停止側になる。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = {"g7b_it_unseeded_gate_key"}, reason = "G7-b IT: 停止遷移の検証用")
    @BatchEndpoint(name = "g7b-it-gated")
    public int gated() {
        BODY_CALLS.incrementAndGet();
        return 1;
    }
}
