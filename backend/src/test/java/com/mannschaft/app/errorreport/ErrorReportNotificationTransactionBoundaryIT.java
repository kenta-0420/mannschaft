package com.mannschaft.app.errorreport;

import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Issue #2990 L11 — errorreport ドメインの通知トランザクション境界の実 DB 検証。
 *
 * <h2>何を実証するテストか</h2>
 * <p>本ロットの是正は<b>因果順序</b>の確立である。是正前、通知は業務トランザクションの内側から
 * {@code @Async("event-pool")} な {@code ErrorReportNotifier} へ渡されていた。別スレッド・別TXへ
 * 逃げているので業務が巻き戻ることは無い（＝{@code ORDERING_ONLY}）。しかし非同期スレッドは
 * <b>commit を待たずに走り出す</b>ため、業務トランザクションが後でロールバックしても
 * 通知だけが残る「逆向きの不整合」が通っていた。</p>
 *
 * <p>そこで本 IT は次の 3 点を実 DB で測る。</p>
 * <ol>
 *   <li><b>因果</b>: 業務トランザクションがロールバックしたとき、通知が<b>1件も出ない</b>こと。
 *       これは是正前のコードでは成立しない（是正前は通知だけが残った）。</li>
 *   <li><b>対照（配送経路が生きている）</b>: 同じ操作がコミットされたときは通知が実際に
 *       {@code notifications} へ現れること。AFTER_COMMIT へ移す是正の最大の失敗形は
 *       「巻き戻らなくなったが、そもそも通知が発火しなくなった」であり、
 *       対照を置かないとその全滅を緑と読み違える。</li>
 *   <li><b>被害半径</b>: 通知の INSERT が実 DB で失敗しても、業務側（RESOLVED への更新）は
 *       コミットされたままであること。</li>
 * </ol>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが
 * 起きずリスナーが発火しないまま「通知0件」で緑になる（偽の緑）。番人
 * {@code TransactionalTestNotificationObservationGuardTest} が機械的に禁じている形でもある。
 * フィクスチャ投入・検証読み取りは {@link TransactionTemplate} / {@link JdbcTemplate} で
 * 明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L11 errorreport ドメインの通知トランザクション境界（実DB）")
class ErrorReportNotificationTransactionBoundaryIT extends AbstractMySqlIntegrationTest {

    /** 解決通知の INSERT だけを実 DB で失敗させる CHECK 制約の名前。 */
    private static final String BLOCK_CONSTRAINT = "chk_issue2990_l11_block_errorreport_notify";

    private static final String NOTIFICATION_TYPE = "ERROR_REPORT_RESOLVED";

    @Autowired
    private ErrorReportService errorReportService;

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    private boolean constraintApplied;

    @AfterEach
    void unblockNotificationInsert() {
        if (constraintApplied) {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
            constraintApplied = false;
        }
    }

    @Test
    @DisplayName("因果: 業務トランザクションがロールバックしたら解決通知は1件も出ない")
    void 業務がロールバックしたら通知も出ない() {
        String nonce = nonce();
        Long userId = insertUser("l11-rollback-" + nonce + "@example.com");
        Long reportId = insertReport(userId, nonce);
        long before = countNotifications(userId);

        // 業務側の失敗でトランザクションをロールバックさせる。
        // 是正前は updateStatus の内側で @Async な notifyResolution が起動済みであり、
        // ロールバックしても通知だけが残った。
        assertThatThrownBy(() -> transactionTemplate.execute(tx -> {
            errorReportService.updateStatus(reportId,
                    new ErrorReportUpdateRequest("RESOLVED", null, null), 1L);
            throw new IllegalStateException("#2990 L11: 業務側の失敗を模す");
        })).isInstanceOf(IllegalStateException.class);

        // 業務側が巻き戻っていること（前提の確認）
        assertThat(statusOf(reportId))
                .as("業務トランザクションはロールバックしている")
                .isEqualTo(ErrorReportStatus.NEW.name());

        // 通知が「出ないままである」ことを一定時間観測する。
        // 単発の 0 件アサートは非同期配送が始まる前に通ってしまい何も検証しない。
        await().during(Duration.ofSeconds(2)).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(userId))
                        .as("業務がコミットされていない以上、解決通知は発火してはならない")
                        .isEqualTo(before));
    }

    @Test
    @DisplayName("対照: 業務トランザクションがコミットされれば解決通知は実際に届く")
    void 業務がコミットされれば通知は届く() {
        String nonce = nonce();
        Long userId = insertUser("l11-commit-" + nonce + "@example.com");
        Long reportId = insertReport(userId, nonce);

        errorReportService.updateStatus(reportId,
                new ErrorReportUpdateRequest("RESOLVED", null, null), 1L);

        assertThat(statusOf(reportId)).isEqualTo(ErrorReportStatus.RESOLVED.name());

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(userId))
                        .as("AFTER_COMMIT の配送経路が生きていること"
                                + "（ここが 0 のままなら是正で通知が全滅している）")
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("被害半径: 解決通知の永続化が実DBで失敗しても RESOLVED への更新はコミットされる")
    void 通知失敗でも業務更新は巻き戻らない() {
        String nonce = nonce();
        Long userId = insertUser("l11-blocked-" + nonce + "@example.com");
        Long reportId = insertReport(userId, nonce);
        blockNotificationInsert();

        assertThatCode(() -> errorReportService.updateStatus(reportId,
                new ErrorReportUpdateRequest("RESOLVED", null, null), 1L))
                .as("通知の永続化失敗が業務処理へ伝播してはならない")
                .doesNotThrowAnyException();

        assertThat(statusOf(reportId))
                .as("通知が実DBで失敗しても RESOLVED への更新は巻き戻らない")
                .isEqualTo(ErrorReportStatus.RESOLVED.name());

        await().during(Duration.ofSeconds(2)).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(userId))
                        .as("通知の INSERT は実DBの CHECK 制約で本当に失敗している（握りつぶしではない）")
                        .isZero());
    }

    // ---- フィクスチャ / ヘルパ ----

    /**
     * 解決通知の INSERT だけを実 DB で失敗させる。
     *
     * <p>MySQL は CHECK 制約の追加時に既存の全行を検証するため、先に対象種別の残骸を消す。
     * DDL は暗黙コミットを伴うのでトランザクション外で実行する。</p>
     */
    private void blockNotificationInsert() {
        jdbcTemplate.update("DELETE FROM notifications WHERE notification_type = ?", NOTIFICATION_TYPE);
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (notification_type <> '" + NOTIFICATION_TYPE + "')");
        constraintApplied = true;
    }

    private static String nonce() {
        return String.valueOf(System.nanoTime());
    }

    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> {
            em.createNativeQuery(
                            "INSERT INTO users ("
                                    + "email, last_name, first_name, display_name, status, "
                                    + "is_searchable, handle_searchable, contact_approval_required, "
                                    + "online_visibility, dm_receive_from, encryption_key_version, "
                                    + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                    + "care_notification_enabled, offline_only, "
                                    + "created_at, updated_at) "
                                    + "VALUES (:email, 'L11', 'テスト', 'L11 報告者', 'ACTIVE', "
                                    + "1, 1, 1, "
                                    + "'NOBODY', 'ANYONE', 1, "
                                    + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                    + "1, 0, "
                                    + "NOW(), NOW())")
                    .setParameter("email", email)
                    .executeUpdate();
            em.flush();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                    .setParameter("email", email)
                    .getSingleResult()).longValue();
        });
    }

    private Long insertReport(Long userId, String nonce) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return transactionTemplate.execute(tx -> errorReportRepository.save(ErrorReportEntity.builder()
                .errorMessage("#2990 L11 通知境界検証")
                .pageUrl("/l11/" + nonce)
                .userId(userId)
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.MEDIUM)
                .errorHash("l11-" + nonce)
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build()).getId());
    }

    private String statusOf(Long reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM error_reports WHERE id = ?", String.class, reportId);
    }

    private Long countNotifications(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id = ?",
                Long.class, NOTIFICATION_TYPE, userId);
    }
}
