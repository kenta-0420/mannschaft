package com.mannschaft.app.shiftbudget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Issue #2990 L13 — シフト予算 閾値超過警告の通知トランザクション境界の実 DB 検証。
 *
 * <h2>何を実証するテストか</h2>
 * <p>是正前、通知は {@code ThresholdAlertEvaluationService#evaluateAndTrigger} の
 * {@code @Transactional(REQUIRES_NEW)} の内側から同期で発火していた。是正後は
 * {@link BudgetThresholdAlertTriggeredEvent} を publish するだけになり、
 * {@code ShiftBudgetThresholdAlertNotificationListener} が
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で配送する。</p>
 *
 * <p>本 IT はその境界を実 DB で 2 方向から測る。</p>
 * <ol>
 *   <li><b>因果</b>: イベントを publish した業務トランザクションがロールバックしたとき、
 *       通知が 1 件も出ないこと。是正前のコード（同期発火）では通知だけが残った。</li>
 *   <li><b>対照（配送経路が生きている）</b>: 同じイベントがコミットされたときは通知が実際に
 *       {@code notifications} へ現れること。AFTER_COMMIT 化の最大の失敗形は
 *       「巻き戻らなくなったが、そもそも通知が発火しなくなった」であり、
 *       対照を置かないとその全滅を緑と読み違える。</li>
 * </ol>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むと
 * コミットが起きずリスナーが発火しないまま「通知0件」で緑になる（偽の緑）。番人
 * {@code TransactionalTestNotificationObservationGuardTest} が機械的に禁じている形でもある。
 * フィクスチャ投入・検証読み取りは {@link TransactionTemplate} / {@link JdbcTemplate} で
 * 明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L13 シフト予算 閾値超過警告の通知境界（実DB）")
class ShiftBudgetThresholdAlertNotificationBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final String NOTIFICATION_TYPE = "SHIFT_BUDGET_THRESHOLD_ALERT";
    private static final Long ALLOCATION_ID = 424242L;
    private static final Long ORG_ID = 1L;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** 配送失敗を模す CHECK 制約の名前。 */
    private static final String BLOCK_CONSTRAINT = "chk_issue2990_l13_block_notify_user";

    private boolean blockedConstraintApplied;

    @AfterEach
    void unblockNotificationInsert() {
        if (blockedConstraintApplied) {
            jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
            blockedConstraintApplied = false;
        }
    }

    @Test
    @DisplayName("因果: イベントを publish した業務トランザクションがロールバックしたら通知は1件も出ない")
    void 業務がロールバックしたら通知も出ない() {
        Long userId = insertUser("l13-rollback-" + nonce() + "@example.com");
        long before = countNotifications(userId);

        assertThatThrownBy(() -> transactionTemplate.execute(tx -> {
            eventPublisher.publishEvent(new BudgetThresholdAlertTriggeredEvent(
                    900L, ALLOCATION_ID, ORG_ID, 100, List.of(userId)));
            throw new IllegalStateException("#2990 L13: 業務側の失敗を模す");
        })).isInstanceOf(IllegalStateException.class);

        // 通知が「出ないままである」ことを一定時間観測する。
        // 単発の 0 件アサートは非同期配送が始まる前に通ってしまい何も検証しない。
        await().during(Duration.ofSeconds(2)).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(userId))
                        .as("業務がコミットされていない以上、閾値超過警告は発火してはならない")
                        .isEqualTo(before));
    }

    @Test
    @DisplayName("対照: イベントがコミットされれば閾値超過警告は実際に届く")
    void 業務がコミットされれば通知は届く() {
        Long userId = insertUser("l13-commit-" + nonce() + "@example.com");

        transactionTemplate.executeWithoutResult(tx ->
                eventPublisher.publishEvent(new BudgetThresholdAlertTriggeredEvent(
                        900L, ALLOCATION_ID, ORG_ID, 100, List.of(userId))));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(userId))
                        .as("AFTER_COMMIT の配送経路が生きていること"
                                + "（ここが 0 のままなら是正で通知が全滅している）")
                        .isEqualTo(1L));
    }

    /**
     * <p><b>当初この検体は「存在しないユーザーID を先頭に置けば配送が落ちる」前提で書いており、CI で落ちた。
     * 前提のほうが誤りだった。</b>実コードを追うと:</p>
     * <ul>
     *   <li>{@code NotificationService#createNotification} は受信者の実在を検証しない
     *       （visibility ガードのみ。{@code SHIFT_BUDGET_ALLOCATION} は {@code ReferenceType} 未対応なので
     *       fail-soft で素通りし、そのまま {@code notifications} へ INSERT する）</li>
     *   <li>{@code notifications.user_id → users} の FK（{@code fk_notifications_user}）は
     *       {@code V100.001__phase2e_drop_misc_user_cascade_fk.sql} で撤廃済みである
     *       （クロスドメイン FK 禁止＝{@code domain_db_design_principles.md} 原則1）</li>
     * </ul>
     * <p>したがって存在しないユーザーID でも通知行は普通に作られる。これは仕様どおりであり欠陥ではない。
     * 「途中失敗」を測るには<b>配送が実際に失敗する条件</b>が要るので、当該受信者への INSERT だけを
     * 実 DB の CHECK 制約で落とす（本戦役で確立した手法）。</p>
     */
    @Test
    @DisplayName("途中失敗: 1人目の配送が実DBで落ちても、残りの受信者へ配送が続き、落ちた受信者は再試行台帳に残る")
    void 一部受信者の失敗で残りが巻き添えにならない() {
        Long blockedUserId = insertUser("l13-blocked-" + nonce() + "@example.com");
        Long deliveredUserId = insertUser("l13-delivered-" + nonce() + "@example.com");
        blockNotificationInsertFor(blockedUserId);
        long failedEventsBefore = countFailedEvents();

        transactionTemplate.executeWithoutResult(tx ->
                eventPublisher.publishEvent(new BudgetThresholdAlertTriggeredEvent(
                        900L, ALLOCATION_ID, ORG_ID, 120,
                        List.of(blockedUserId, deliveredUserId))));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(deliveredUserId))
                        .as("先頭の受信者が実DBで落ちても、後続の受信者へ配送が続くこと（被害半径の分離）")
                        .isEqualTo(1L));

        assertThat(countNotifications(blockedUserId))
                .as("CHECK 制約で本当に INSERT が落ちていること（握りつぶしの偽緑ではない）")
                .isZero();

        // Codex 検分 P1-a: 落ちた受信者が NOTIFICATION_SEND の failed event に残ること。
        // 残らなければ本 PR で作った再送の仕組みがそもそも起動せず、通知は回復不能に失われる。
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countFailedEvents())
                        .as("""
                                受信者単位の配送失敗が再試行台帳に残らないと、
                                「alert は確定済みなのに通知は失われ、再送経路も無い」状態になる。""")
                        .isEqualTo(failedEventsBefore + 1));
        assertThat(latestFailedEventUserIds())
                .as("再送対象は失敗した受信者だけ（成功済みを混ぜると再送で重複配信になる）")
                .containsExactly(blockedUserId);
    }

    // ---- フィクスチャ / ヘルパ ----

    /**
     * 指定ユーザー宛の通知 INSERT だけを実 DB で失敗させる。
     *
     * <p>MySQL は CHECK 制約の追加時に既存の全行を検証するため、対象ユーザーの行が無いことが前提
     * （新規作成したユーザーなので 0 件）。DDL は暗黙コミットを伴うのでトランザクション外で実行する。</p>
     */
    private void blockNotificationInsertFor(Long userId) {
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (user_id <> " + userId + ")");
        blockedConstraintApplied = true;
    }

    private Long countFailedEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_budget_failed_events "
                        + "WHERE event_type = 'NOTIFICATION_SEND' AND source_id = ?",
                Long.class, ALLOCATION_ID);
    }

    private String latestFailedEventPayload() {
        return jdbcTemplate.queryForObject(
                "SELECT payload FROM shift_budget_failed_events "
                        + "WHERE event_type = 'NOTIFICATION_SEND' AND source_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, ALLOCATION_ID);
    }

    private List<Long> latestFailedEventUserIds() {
        try {
            JsonNode payload = objectMapper.readTree(latestFailedEventPayload());
            return objectMapper.convertValue(payload.path("user_ids"), new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("failed event payload の JSON 解析に失敗しました", e);
        }
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
                                    + "VALUES (:email, 'L13', 'テスト', 'L13 予算管理者', 'ACTIVE', "
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

    private Long countNotifications(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id = ?",
                Long.class, NOTIFICATION_TYPE, userId);
    }
}
