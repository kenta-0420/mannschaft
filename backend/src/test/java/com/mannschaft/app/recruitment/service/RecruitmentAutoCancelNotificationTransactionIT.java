package com.mannschaft.app.recruitment.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.notification.confirmable.event.RecruitmentAutoCancelledNotificationListener;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.event.RecruitmentAutoCancelledNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260930-1932 AC-4 / AC-5 / AC-6: 自動キャンセルバッチの通知TX分離の実DB検証。
 *
 * <h2>現状の欠陥（red の理由）</h2>
 * <p>{@code RecruitmentAutoCancelBatch#processSingleListing}（{@code @Transactional}）の内側で
 * {@code ConfirmableNotificationService#send} を同期で呼び、{@code createdBy=null} のため
 * {@code userRepository.findById(null)} が例外 → 業務TXが rollback-only → catch しても commit 時に
 * {@code UnexpectedRollbackException} となり、参加者ありの自動キャンセルが<b>毎回巻き戻る</b>。
 * ORG スコープでは加えて課金（猶予超過で CREDIT_INSUFFICIENT）でも巻き戻る。</p>
 *
 * <h2>根治後の契約</h2>
 * <p>業務TX内ではイベント publish のみ。AFTER_COMMIT + {@code @Async} の
 * {@link RecruitmentAutoCancelledNotificationListener} が {@code source_type='RECRUITMENT_AUTO_CANCEL'}・
 * {@code source_id=listingId} の確認通知を作る。クラスに {@code @Transactional} は付けない
 * （付けると AFTER_COMMIT が発火せず偽の緑になる）。DB・TX境界・自前 Bean はモックしない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-1932 AC-4/5/6: 自動キャンセルの通知は業務コミット後に別TXで送る（実DB）")
class RecruitmentAutoCancelNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_TYPE = "RECRUITMENT_AUTO_CANCEL";
    private static final Long ORG_ID_BASE = 87_000_000L;
    private static final Long TEAM_ID_BASE = 86_000_000L;
    /** 実在しない利用者ID（参加者行には FK が無いが、確認通知の受信者行には FK があり送信が失敗する）。 */
    private static final Long GHOST_USER_ID = 8_999_999_999L;

    @Autowired
    private RecruitmentAutoCancelBatch batch;

    @Autowired
    private RecruitmentAutoCancelledNotificationListener listener;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentParticipantRepository participantRepository;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private final List<Long> listingIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private Long organizationId;

    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(RecruitmentAutoCancelledNotificationListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        listenerLogger.detachAppender(appender);
        for (Long listingId : listingIds) {
            List<Long> notificationIds = jdbc.queryForList(
                    "SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?",
                    Long.class, SOURCE_TYPE, listingId);
            for (Long nid : notificationIds) {
                jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?", nid);
                jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", nid);
            }
            jdbc.update("DELETE FROM recruitment_participant_history WHERE listing_id = ?", listingId);
            jdbc.update("DELETE FROM recruitment_participants WHERE listing_id = ?", listingId);
            jdbc.update("DELETE FROM recruitment_listings WHERE id = ?", listingId);
        }
        for (Long uid : userIds) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", uid);
        }
        if (organizationId != null) {
            jdbc.update("DELETE FROM notification_monthly_usage WHERE organization_id = ?", organizationId);
            jdbc.update("DELETE FROM organization_notification_balances WHERE organization_id = ?", organizationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    // ------------------------------------------------------------------
    // AC-4: 参加者ありの自動キャンセルが commit され、参加者全員に確認通知が1件作られる
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-4(TEAM): 参加者ありの最小定員未達募集が AUTO_CANCELLED で commit され、参加者全員に確認通知1件")
    void AC4_チーム募集の自動キャンセルがコミットされ参加者全員に通知1件() {
        List<Long> users = newUsers("team", 3);
        Long teamId = TEAM_ID_BASE + System.nanoTime() % 1_000_000L;
        Long listingId = createOpenListing(RecruitmentScopeType.TEAM, teamId, users.get(0), 2);
        addParticipants(listingId, users.subList(1, 3));

        assertAutoCancelCommittedAndNotified(listingId, users.subList(1, 3), "TEAM", teamId);
    }

    @Test
    @DisplayName("AC-4(ORG): 猶予超過・残高0 の組織の募集でも自動キャンセルが commit され、参加者全員に確認通知1件"
            + "（クレジット残高行は不変）")
    void AC4_組織募集は猶予超過残高0でも自動キャンセルがコミットされ通知1件() {
        List<Long> users = newUsers("org", 3);
        organizationId = ORG_ID_BASE + System.nanoTime() % 1_000_000L;
        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(organizationId)
                .freeUsedThisMonth(10_000L)
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodStartAt(LocalDateTime.now().minusHours(72).minusSeconds(1)) // 72h+1s 超過
                .gracePeriodDebt(1L)
                .build());
        Long freeUsedBefore = freeUsedThisMonth();

        Long listingId = createOpenListing(RecruitmentScopeType.ORGANIZATION, organizationId, users.get(0), 2);
        addParticipants(listingId, users.subList(1, 3));

        assertAutoCancelCommittedAndNotified(listingId, users.subList(1, 3), "ORGANIZATION", organizationId);
        assertThat(freeUsedThisMonth())
                .as("AC-4(ORG): システム発の自動キャンセル通知は課金されない（無料枠使用量不変）")
                .isEqualTo(freeUsedBefore);
    }

    @Test
    @DisplayName("AC-4(PERSONAL→PLATFORM): 個人募集の自動キャンセルが commit され、PLATFORM スコープで参加者全員に確認通知1件")
    void AC4_個人募集の自動キャンセルがコミットされPLATFORMで通知1件() {
        List<Long> users = newUsers("personal", 3);
        Long ownerId = users.get(0);
        Long listingId = createOpenListing(RecruitmentScopeType.PERSONAL, ownerId, ownerId, 2);
        addParticipants(listingId, users.subList(1, 3));

        assertAutoCancelCommittedAndNotified(listingId, users.subList(1, 3), "PLATFORM", ownerId);
    }

    @Test
    @DisplayName("AC-4(空): 受信者0件の自動キャンセルは commit され、確認通知を作らない")
    void AC4_受信者0件なら通知を作らない() {
        List<Long> users = newUsers("empty", 1);
        Long teamId = TEAM_ID_BASE + 1 + System.nanoTime() % 1_000_000L;
        Long listingId = createOpenListing(RecruitmentScopeType.TEAM, teamId, users.get(0), 0);

        Throwable thrown = catchThrowable(() -> batch.processSingleListing(listingId, LocalDateTime.now()));

        assertThat(thrown).as("AC-4(空): 自動キャンセル本体は例外なく commit される").isNull();
        assertThat(listingStatus(listingId)).isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED.name());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> countAutoCancelNotifications(listingId) == 0L);
    }

    // ------------------------------------------------------------------
    // AC-5: 並行2回発火でも通知は1件（DB 一意性で競合時も保証）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: 同一 listing の通知イベントを2スレッドで同時に発火しても、確認通知は1件だけ作られる")
    void AC5_並行2回発火でも確認通知は1件() throws Exception {
        List<Long> users = newUsers("concurrent", 3);
        Long teamId = TEAM_ID_BASE + 2 + System.nanoTime() % 1_000_000L;
        Long listingId = createOpenListing(RecruitmentScopeType.TEAM, teamId, users.get(0), 2);
        RecruitmentAutoCancelledNotificationEvent event = new RecruitmentAutoCancelledNotificationEvent(
                listingId, RecruitmentScopeType.TEAM, teamId, users.subList(1, 3));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    listener.onAutoCancelled(event);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(countAutoCancelNotifications(listingId))
                        .as("AC-5: 並行2回発火の後、確認通知（source_type=RECRUITMENT_AUTO_CANCEL）が作られている")
                        .isGreaterThanOrEqualTo(1L));
        // 2本目の遅延到着を待ってもなお1件であること（冪等性は exists 確認だけでなく競合時も保証される）
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertThat(countAutoCancelNotifications(listingId))
                        .as("AC-5: 同一 listing の確認通知はちょうど1件")
                        .isEqualTo(1L));
    }

    // ------------------------------------------------------------------
    // AC-6: 通知送信が失敗しても自動キャンセル本体は commit 済み、ERROR ログ1行（listingId 入り）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: 通知送信が例外（受信者の FK 違反）を投げても、自動キャンセル本体は commit 済みのまま、"
            + "listingId 入りの ERROR ログが1行出る")
    void AC6_通知失敗でも自動キャンセルはコミット済みでERRORログ() {
        List<Long> users = newUsers("fail", 2);
        Long teamId = TEAM_ID_BASE + 3 + System.nanoTime() % 1_000_000L;
        Long listingId = createOpenListing(RecruitmentScopeType.TEAM, teamId, users.get(0), 2);
        // 実在しない利用者を参加者に含める → 確認通知の受信者行の FK 違反で送信が失敗する。
        addParticipants(listingId, List.of(users.get(1), GHOST_USER_ID));

        Throwable thrown = catchThrowable(() -> batch.processSingleListing(listingId, LocalDateTime.now()));

        assertThat(thrown).as("AC-6: 通知の失敗は自動キャンセル本体へ伝播しない").isNull();
        assertThat(listingStatus(listingId))
                .as("AC-6: 自動キャンセル本体は commit 済み")
                .isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED.name());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .filter(e -> e.getFormattedMessage().contains(String.valueOf(listingId)))
                    .toList();
            assertThat(errors)
                    .as("AC-6: 通知リスナーが listingId 入りの ERROR ログを1行出す")
                    .hasSize(1);
        });
        assertThat(listingStatus(listingId))
                .as("AC-6: 通知失敗の後も自動キャンセルは巻き戻らない")
                .isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED.name());
        assertThat(countAutoCancelNotifications(listingId))
                .as("AC-6: 失敗した通知は通知TXごと巻き戻り、半端な確認通知行は残らない")
                .isZero();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private void assertAutoCancelCommittedAndNotified(
            Long listingId, List<Long> participantUserIds, String expectedScopeType, Long expectedScopeId) {
        Throwable thrown = catchThrowable(() -> batch.processSingleListing(listingId, LocalDateTime.now()));

        assertThat(thrown)
                .as("AC-4: 参加者ありの自動キャンセルは例外なく commit される"
                        + "（現状は業務TX内の同期 send が rollback-only を立て UnexpectedRollbackException になる）")
                .isNull();
        assertThat(listingStatus(listingId))
                .as("AC-4: 募集は AUTO_CANCELLED で commit されている")
                .isEqualTo(RecruitmentListingStatus.AUTO_CANCELLED.name());
        assertThat(jdbc.queryForList(
                "SELECT status FROM recruitment_participants WHERE listing_id = ?", String.class, listingId))
                .as("AC-4: 参加者は全員 CANCELLED で commit されている")
                .hasSize(participantUserIds.size())
                .allMatch(RecruitmentParticipantStatus.CANCELLED.name()::equals);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recruitment_participant_history WHERE listing_id = ? AND change_reason = 'AUTO_CANCEL'",
                Long.class, listingId))
                .as("AC-4: 参加者ごとの自動キャンセル履歴行がある")
                .isEqualTo((long) participantUserIds.size());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(countAutoCancelNotifications(listingId))
                        .as("AC-4/AC-5: AFTER_COMMIT 後に source_type=RECRUITMENT_AUTO_CANCEL・source_id=listingId の確認通知が1件作られる")
                        .isEqualTo(1L));
        Long notificationId = jdbc.queryForObject(
                "SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?",
                Long.class, SOURCE_TYPE, listingId);
        assertThat(jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId))
                .as("AC-4: 参加者全員が受信者になっている")
                .containsExactlyInAnyOrderElementsOf(participantUserIds);
        assertThat(jdbc.queryForObject(
                "SELECT scope_type FROM confirmable_notifications WHERE id = ?", String.class, notificationId))
                .as("AC-4: 通知スコープ種別")
                .isEqualTo(expectedScopeType);
        assertThat(jdbc.queryForObject(
                "SELECT scope_id FROM confirmable_notifications WHERE id = ?", Long.class, notificationId))
                .as("AC-4: 通知スコープID")
                .isEqualTo(expectedScopeId);
    }

    private List<Long> newUsers(String label, int count) {
        emailPrefix = "cmp1932-ac4-" + label + "-" + UUID.randomUUID();
        List<Long> ids = ConfirmableFanoutFixture.insertUsers(transactionManager, em, count, emailPrefix);
        userIds.addAll(ids);
        return ids;
    }

    private Long createOpenListing(RecruitmentScopeType scopeType, Long scopeId, Long createdBy, int confirmedCount) {
        LocalDateTime now = LocalDateTime.now();
        Long id = new TransactionTemplate(transactionManager).execute(status -> listingRepository.save(
                RecruitmentListingEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .categoryId(1L)
                        .title("CMP-260930-1932 自動キャンセル試練")
                        .participationType(RecruitmentParticipationType.INDIVIDUAL)
                        .startAt(now.plusDays(2))
                        .endAt(now.plusDays(2).plusHours(2))
                        .applicationDeadline(now.minusHours(1))
                        .autoCancelAt(now.minusMinutes(30))
                        .capacity(10)
                        .minCapacity(5)
                        .confirmedCount(confirmedCount)
                        .visibility(RecruitmentVisibility.SCOPE_ONLY)
                        .status(RecruitmentListingStatus.OPEN)
                        .createdBy(createdBy)
                        .build()).getId());
        listingIds.add(id);
        return id;
    }

    private void addParticipants(Long listingId, List<Long> participantUserIds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long uid : participantUserIds) {
                participantRepository.save(RecruitmentParticipantEntity.builder()
                        .listingId(listingId)
                        .participantType(RecruitmentParticipantType.USER)
                        .userId(uid)
                        .appliedBy(uid)
                        .status(RecruitmentParticipantStatus.CONFIRMED)
                        .build());
            }
        });
    }

    private String listingStatus(Long listingId) {
        return jdbc.queryForObject("SELECT status FROM recruitment_listings WHERE id = ?", String.class, listingId);
    }

    private long countAutoCancelNotifications(Long listingId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notifications WHERE source_type = ? AND source_id = ?",
                Long.class, SOURCE_TYPE, listingId);
        return c == null ? 0 : c;
    }

    private Long freeUsedThisMonth() {
        return jdbc.queryForObject(
                "SELECT free_used_this_month FROM organization_notification_balances WHERE organization_id = ?",
                Long.class, organizationId);
    }
}
