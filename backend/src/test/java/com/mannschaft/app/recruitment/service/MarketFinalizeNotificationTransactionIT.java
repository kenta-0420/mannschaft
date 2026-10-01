package com.mannschaft.app.recruitment.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.event.MarketListingReachedFullEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260930-1932 AC-7 / AC-8: 市（F22.1）の最終認証通知を申込の業務TXから切り離す実DB検証。
 *
 * <h2>現状の欠陥（red の理由）</h2>
 * <p>{@code RecruitmentParticipantService#apply}（{@code @Transactional}）が FULL 到達時に
 * {@code MarketFinalizeService#sendFinalizeConfirmation} を<b>同期で</b>呼び、その中で
 * {@code ConfirmableNotificationService#sendFromSource} が走る。catch が無いため、組織の猶予超過
 * （{@code CREDIT_INSUFFICIENT}）や受信者行の失敗で<b>申込そのものが失敗</b>し申込者にエラーが露出する。
 * 設計（F22.1 02_api_design §6.1）はイベントリスナ経由で送る。</p>
 *
 * <h2>根治後の契約</h2>
 * <ul>
 *   <li>AC-7: 定員到達の申込は ORG 猶予超過・残高0 でも確定し、最終認証通知は AFTER_COMMIT 後に作られる。</li>
 *   <li>AC-8: 通知が失敗しても申込は確定、ERROR ログ。業務TXがロールバックしたら通知は作らない。
 *       リスナーは最新状態を再取得し FULL のときだけ送る。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けると AFTER_COMMIT が発火しない）。
 * 既存 {@link MarketFinalizeIntegrationTest} と違い {@code MarketFinalizeService} もモックしない
 * （DB・TX境界・自前 Bean はモックしない方針）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-1932 AC-7/8: 最終認証通知は申込の業務コミット後に送る（実DB）")
class MarketFinalizeNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_TYPE = MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE;
    private static final Long ORG_ID_BASE = 85_000_000L;
    private static final Long TEAM_ID_BASE = 84_000_000L;
    /** 実在しない利用者ID（札主に置くと、ADMIN 不在時のフォールバック受信者行が FK 違反で失敗する）。 */
    private static final Long GHOST_USER_ID = 8_999_999_998L;

    @Autowired
    private RecruitmentParticipantService participantService;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private MarketFinalizeConfirmationListener finalizeListener;

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

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> appender;
    /** test プロファイルの root は WARN のため、判定スキップの INFO を拾うには発生元ロガーを一時的に INFO にする。 */
    private Logger finalizeServiceLogger;
    private Level finalizeServiceLoggerLevel;

    @BeforeEach
    void attachAppender() {
        // 最終認証リスナーのクラス名は出陣で決まるため、ルートロガーで listingId 入りの ERROR を拾う。
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        finalizeServiceLogger = (Logger) LoggerFactory.getLogger(MarketFinalizeService.class);
        finalizeServiceLoggerLevel = finalizeServiceLogger.getLevel();
        finalizeServiceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void cleanUp() {
        rootLogger.detachAppender(appender);
        finalizeServiceLogger.setLevel(finalizeServiceLoggerLevel);
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

    @Test
    @DisplayName("AC-7: ORG 猶予超過・残高0 でも、定員到達（FULL）の申込は確定し CREDIT_INSUFFICIENT を返さない。"
            + "最終認証通知は AFTER_COMMIT 後に札主へ作られ、残高行は不変")
    void AC7_組織猶予超過残高0でも定員到達の申込は確定し最終認証通知は後から届く() {
        List<Long> users = newUsers("ac7", 2);
        Long ownerId = users.get(0);
        Long applicantId = users.get(1);
        organizationId = ORG_ID_BASE + System.nanoTime() % 1_000_000L;
        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(organizationId)
                .freeUsedThisMonth(10_000L)
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodStartAt(LocalDateTime.now().minusHours(73))
                .gracePeriodDebt(1L)
                .build());
        Long freeUsedBefore = freeUsedThisMonth();
        Long listingId = createPublicListing(RecruitmentScopeType.ORGANIZATION, organizationId, ownerId);

        Throwable thrown = catchThrowable(() -> participantService.apply(listingId, applicantId, userRequest()));

        assertThat(thrown)
                .as("AC-7: 定員到達の申込は通知クレジットの状態にかかわらず確定する"
                        + "（現状は同期 sendFromSource の consume が CREDIT_INSUFFICIENT を投げ申込ごと失敗する）")
                .isNull();
        assertThat(listingStatus(listingId)).as("AC-7: 札は FULL で commit").isEqualTo(RecruitmentListingStatus.FULL.name());
        assertThat(participantStatus(listingId, applicantId))
                .as("AC-7: 申込は CONFIRMED で commit")
                .isEqualTo(RecruitmentParticipantStatus.CONFIRMED.name());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(countFinalizeNotifications(listingId))
                        .as("AC-7: 最終認証通知（source_type=MARKET_FINALIZE）が AFTER_COMMIT 後に1件作られる")
                        .isEqualTo(1L));
        Long notificationId = jdbc.queryForObject(
                "SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?",
                Long.class, SOURCE_TYPE, listingId);
        assertThat(jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId))
                .as("AC-7: ADMIN 不在の組織では札主本人が受信者（既存のフォールバックを維持）")
                .containsExactly(ownerId);
        assertThat(freeUsedThisMonth())
                .as("AC-7: システム発の最終認証通知は課金されない（無料枠使用量不変）")
                .isEqualTo(freeUsedBefore);
    }

    @Test
    @DisplayName("AC-8: 最終認証通知の送信が失敗（受信者行の FK 違反）しても申込は確定し、listingId 入りの ERROR ログが出る")
    void AC8_最終認証通知が失敗しても申込は確定しERRORログ() {
        List<Long> users = newUsers("ac8fail", 1);
        Long applicantId = users.get(0);
        Long teamId = TEAM_ID_BASE + System.nanoTime() % 1_000_000L;
        // 札主を実在しない利用者にする → ADMIN 不在のフォールバック受信者行が FK 違反で失敗する。
        Long listingId = createPublicListing(RecruitmentScopeType.TEAM, teamId, GHOST_USER_ID);

        Throwable thrown = catchThrowable(() -> participantService.apply(listingId, applicantId, userRequest()));

        assertThat(thrown)
                .as("AC-8: 通知の失敗は申込の業務TXへ伝播しない（現状は同期送信の失敗で申込ごと失敗する）")
                .isNull();
        assertThat(participantStatus(listingId, applicantId))
                .as("AC-8: 申込は CONFIRMED で commit")
                .isEqualTo(RecruitmentParticipantStatus.CONFIRMED.name());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(appender.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .filter(e -> e.getFormattedMessage().contains(String.valueOf(listingId)))
                        .toList())
                        .as("AC-8: 最終認証通知の失敗は listingId 入りの ERROR ログで可視化される")
                        .isNotEmpty());
        assertThat(participantStatus(listingId, applicantId))
                .as("AC-8: 通知失敗の後も申込は巻き戻らない")
                .isEqualTo(RecruitmentParticipantStatus.CONFIRMED.name());
        assertThat(countFinalizeNotifications(listingId))
                .as("AC-8: 失敗した通知は通知TXごと巻き戻り、半端な確認通知行は残らない")
                .isZero();
    }

    @Test
    @DisplayName("AC-8: 申込の業務TXがロールバックしたら、最終認証通知は作られない（AFTER_COMMIT が発火しない）")
    void AC8_業務TXロールバック時は最終認証通知を作らない() {
        List<Long> users = newUsers("ac8rb", 2);
        Long teamId = TEAM_ID_BASE + 1 + System.nanoTime() % 1_000_000L;
        Long listingId = createPublicListing(RecruitmentScopeType.TEAM, teamId, users.get(0));

        Throwable thrown = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            participantService.apply(listingId, users.get(1), userRequest());
            throw new IllegalStateException("強制ロールバック（AC-8 検証用）");
        }));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("強制ロールバック");
        assertThat(listingStatus(listingId)).as("前提: 業務TXはロールバック済み").isEqualTo(RecruitmentListingStatus.OPEN.name());
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                .until(() -> countFinalizeNotifications(listingId) == 0L);
    }

    @Test
    @DisplayName("AC-8: リスナーは最新状態を再取得し FULL のときだけ送る — 同一TX内で FULL から OPEN に戻って"
            + "コミットされた札には最終認証通知を作らない")
    void AC8_コミット時点でFULLでなければ最終認証通知を作らない() {
        List<Long> users = newUsers("ac8latest", 2);
        Long teamId = TEAM_ID_BASE + 2 + System.nanoTime() % 1_000_000L;
        Long listingId = createPublicListing(RecruitmentScopeType.TEAM, teamId, users.get(0));

        Throwable thrown = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            participantService.apply(listingId, users.get(1), userRequest());
            // 同一の業務TX内で（例: 直後の取消で）札が OPEN に戻った状態を作り、そのまま commit する。
            jdbc.update("UPDATE recruitment_listings SET status = 'OPEN' WHERE id = ?", listingId);
        }));

        assertThat(thrown).as("前提: 申込と状態戻しは commit される").isNull();
        assertThat(listingStatus(listingId)).as("前提: commit 時点の札は OPEN").isEqualTo(RecruitmentListingStatus.OPEN.name());
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertThat(countFinalizeNotifications(listingId))
                        .as("AC-8: commit 時点で FULL でない札に最終認証通知を作ってはならない"
                                + "（現状は業務TX内で同期送信済みのため1件残る）")
                        .isZero());
    }


    @Test
    @DisplayName("Codex P2-①: 状態確認から通知作成までの間に参加者キャンセルで札が OPEN に戻ったら、"
            + "最終認証通知を作らない（札の行ロック下で状態を再確認して直列化する）")
    void P2_確認と作成の間にOPENへ戻った札には最終認証通知を作らない() {
        List<Long> users = newUsers("p2cancel", 1);
        Long teamId = TEAM_ID_BASE + 3 + System.nanoTime() % 1_000_000L;
        Long listingId = createPublicListing(RecruitmentScopeType.TEAM, teamId, users.get(0));
        markFull(listingId);

        // 参加者キャンセルの業務TXを模す: cancelMyApplication と同じく札行を FOR UPDATE で握ったまま、
        // その間に最終認証リスナーを走らせ、後から FULL→OPEN（decrementConfirmedAtomic 相当）で commit する。
        // 固定 sleep ではなく、リスナーが札行のロック待ちに入ったこと（data_locks の WAITING）を実測してから進める。
        // 札行で直列化していなければリスナーはロック待ちに入らず、ロック保持中に通知を作り終える（→ 下の検証で red）。
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM recruitment_listings WHERE id = ? FOR UPDATE", Long.class, listingId);
            finalizeListener.onReachedFull(new MarketListingReachedFullEvent(listingId)); // @Async: 別スレッド
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).until(() ->
                    waitingRowLocks("recruitment_listings", listingId) == 1
                            || countFinalizeNotifications(listingId) > 0
                            || skipLogCount(listingId, "コミット時点で FULL ではない") > 0);
            jdbc.update("UPDATE recruitment_listings SET status = 'OPEN', confirmed_count = 0 WHERE id = ?", listingId);
        });

        assertThat(listingStatus(listingId)).as("前提: キャンセルの commit 後の札は OPEN")
                .isEqualTo(RecruitmentListingStatus.OPEN.name());
        // リスナーの処理が終わる（＝通知を作った、または OPEN を見てスキップした）まで待つ。
        await().atMost(Duration.ofSeconds(10)).until(() ->
                countFinalizeNotifications(listingId) > 0
                        || skipLogCount(listingId, "コミット時点で FULL ではない") > 0);
        assertThat(countFinalizeNotifications(listingId))
                .as("Codex P2-①: キャンセルで OPEN に戻った札に無効な最終認証通知を作ってはならない")
                .isZero();
    }

    @Test
    @DisplayName("Codex P2-②: 同一札の FULL 到達イベントが2件並行に処理されても、ACTIVE な最終認証通知は1件だけ作る")
    void P2_同一札の2イベント並行処理でもACTIVEな最終認証通知は1件() {
        List<Long> users = newUsers("p2dup", 1);
        Long teamId = TEAM_ID_BASE + 4 + System.nanoTime() % 1_000_000L;
        Long listingId = createPublicListing(RecruitmentScopeType.TEAM, teamId, users.get(0));
        markFull(listingId);

        // FULL→OPEN→再FULL で2件の AFTER_COMMIT イベントが event-pool で並行に処理される状況を作る。
        // 受信者（札主 = ADMIN 不在時のフォールバック）の users 行を握り、通知の受信者行 INSERT の FK 検査
        // （users 行の共有ロック）で両スレッドを「ACTIVE 不在を確認した後・通知をコミットする前」に止める。
        // 札行で直列化していなければ、両者とも ACTIVE 不在を見たまま待ち、解放後に2件とも作ってしまう。
        // 固定 sleep ではなく、2スレッドがともにロック待ちに入ったことを data_locks で実測してから解放する。
        // 直列化ありなら「先行=users 行（FK 検査）・後続=札行」で各1件、直列化なしなら users 行に2件となり、
        // どちらの場合も待ち合計が2件に達してから解放するため、ロック解放後に遅れて走る偽緑を排除できる。
        Long ownerId = users.get(0);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, ownerId);
            finalizeListener.onReachedFull(new MarketListingReachedFullEvent(listingId));
            finalizeListener.onReachedFull(new MarketListingReachedFullEvent(listingId));
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).until(() ->
                    waitingRowLocks("users", ownerId) + waitingRowLocks("recruitment_listings", listingId) == 2);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(activeFinalizeNotifications(listingId) + skipLogCount(listingId, "既に未確認で存在する"))
                        .as("前提: 2件のイベントがどちらも処理された（作成1件＋再送スキップ1件）")
                        .isGreaterThanOrEqualTo(2L));
        assertThat(activeFinalizeNotifications(listingId))
                .as("Codex P2-②: 同一札（source_id）の ACTIVE な最終認証通知は1件でなければならない（重複通知）")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private void markFull(Long listingId) {
        jdbc.update("UPDATE recruitment_listings SET status = 'FULL', confirmed_count = 1 WHERE id = ?", listingId);
    }

    /**
     * 指定テーブルの主キー行に対して InnoDB のロック待ち（{@code LOCK_STATUS='WAITING'}）に入っている件数。
     *
     * <p>{@code performance_schema} はアプリ用の test 利用者では読めないため、同じコンテナへ root で別接続して覗く
     * （Testcontainers の MySQL は root のパスワードを利用者のパスワードと同じにする）。ロック保持側の接続とは別物。</p>
     */
    private static long waitingRowLocks(String table, Long id) throws SQLException {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM performance_schema.data_locks"
                             + " WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ? AND INDEX_NAME = 'PRIMARY'"
                             + " AND LOCK_STATUS = 'WAITING' AND LOCK_DATA = ?")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, table);
            ps.setString(3, String.valueOf(id));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long activeFinalizeNotifications(Long listingId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notifications WHERE source_type = ? AND source_id = ? AND status = 'ACTIVE'",
                Long.class, SOURCE_TYPE, listingId);
        return c == null ? 0 : c;
    }

    private long skipLogCount(Long listingId, String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains(fragment) && msg.contains("listingId=" + listingId))
                .count();
    }

    private static ApplyToRecruitmentRequest userRequest() {
        return new ApplyToRecruitmentRequest(RecruitmentParticipantType.USER, null, null);
    }

    private List<Long> newUsers(String label, int count) {
        emailPrefix = "cmp1932-mf-" + label + "-" + UUID.randomUUID();
        List<Long> ids = ConfirmableFanoutFixture.insertUsers(transactionManager, em, count, emailPrefix);
        userIds.addAll(ids);
        return ids;
    }

    /** capacity=1 の公開札（この申込1件で OPEN→FULL に遷移する）。 */
    private Long createPublicListing(RecruitmentScopeType scopeType, Long scopeId, Long createdBy) {
        LocalDateTime now = LocalDateTime.now();
        Long id = new TransactionTemplate(transactionManager).execute(status -> listingRepository.save(
                RecruitmentListingEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .categoryId(1L)
                        .title("CMP-260930-1932 最終認証試練")
                        .participationType(RecruitmentParticipationType.INDIVIDUAL)
                        .startAt(now.plusDays(7))
                        .endAt(now.plusDays(7).plusHours(2))
                        .applicationDeadline(now.plusDays(5))
                        .autoCancelAt(now.plusDays(5))
                        .capacity(1)
                        .minCapacity(1)
                        .confirmedCount(0)
                        .visibility(RecruitmentVisibility.PUBLIC)
                        .status(RecruitmentListingStatus.OPEN)
                        .createdBy(createdBy)
                        .build()).getId());
        listingIds.add(id);
        return id;
    }

    private String listingStatus(Long listingId) {
        return jdbc.queryForObject("SELECT status FROM recruitment_listings WHERE id = ?", String.class, listingId);
    }

    private String participantStatus(Long listingId, Long userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM recruitment_participants WHERE listing_id = ? AND user_id = ?",
                String.class, listingId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countFinalizeNotifications(Long listingId) {
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
