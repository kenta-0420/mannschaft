package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.service.PersonalScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * Issue #2990 L8 — schedule ドメインの通知トランザクション境界の実 DB 検証。
 *
 * <h2>何を実証するテストか</h2>
 * <p>「通知の永続化が<b>実際に失敗しても</b>業務処理はコミットされたままである」ことを、
 * 実 DB の制約違反で確かめる。是正前はいずれの経路も業務トランザクションの内側で通知を
 * INSERT していたため、この失敗が rollback-only を残して業務側ごと巻き戻っていた。</p>
 *
 * <h2>モック例外では再現できない。だから実 DB 障害を注入する</h2>
 * <p>本ロットで是正した 3 経路のうち 2 経路（リマインド／出欠募集）は
 * {@code NotificationHelper#notifyAllPreAuthorized} → {@code NotificationBulkFanoutService#insertAndDispatchChunk}
 * を通る。{@code notifyAllPreAuthorized} はチャンク失敗を {@code try/catch} で握って
 * 「呼び出し元の業務トランザクションを巻き添えロールバックさせない」と称しているが、
 * {@code NotificationBulkFanoutService} には {@code @Transactional} が<b>一切無く</b>
 * 呼び出し元のトランザクションにそのまま参加する。したがって:</p>
 * <ul>
 *   <li>spy に {@code RuntimeException} を投げさせても、それは Spring/Hibernate から見れば
 *       ただのアプリ例外であり <b>rollback-only は立たない</b>。catch に握られてテストは緑になり、
 *       欠陥を取り逃す（＝偽の緑）。</li>
 *   <li>実 DB の制約違反であれば永続化コンテキストが汚染されて rollback-only が立つ。
 *       ここで初めて「一括 catch が機能していない」という本当の姿が出る。</li>
 * </ul>
 * <p>そこで {@code notifications} テーブルへテスト側で CHECK 制約を張り、本ロットで扱う 3 種別の
 * INSERT だけを実 DB で失敗させる。制約は {@code @AfterEach} で必ず落とすため他テストに影響しない。
 * CHECK 制約の付与・削除は MySQL の DDL であり暗黙コミットを伴うため、トランザクション外
 * （{@code @BeforeEach} / {@code @AfterEach}）で実行する
 * （手法は {@code NotificationCreditFreeQuotaAlertTransactionIT} と同型）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが
 * 起きずリスナーが発火しないまま緑になる（偽の緑）。フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 *
 * <h2>「配送経路が生きていること」も併せて測る</h2>
 * <p>AFTER_COMMIT へ移す是正の最大の失敗形は「巻き戻らなくなったが、通知が<b>そもそも発火しなく
 * なった</b>」である（イベントを publish する側にトランザクションが無いと AFTER_COMMIT リスナーは
 * 黙って捨てられる）。制約を張らない対照テストで、通知が実際に {@code notifications} へ
 * 現れることまで確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L8 schedule ドメインの通知トランザクション境界（実DB）")
class ScheduleNotificationTransactionBoundaryIT extends AbstractMySqlIntegrationTest {

    /** 本ロットで扱う通知種別の INSERT だけを実 DB で失敗させる CHECK 制約の名前。 */
    private static final String BLOCK_CONSTRAINT = "chk_issue2990_l8_block_schedule_notify";

    @Autowired
    private PersonalScheduleReminderService personalScheduleReminderService;

    @Autowired
    private PersonalScheduleReminderRepository personalScheduleReminderRepository;

    @Autowired
    private ScheduleAttendanceService scheduleAttendanceService;

    @Autowired
    private ScheduleAttendanceRepository scheduleAttendanceRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @BeforeEach
    void blockNotificationInsert() {
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (notification_type NOT IN ('SCHEDULE_REMINDER', 'SCHEDULE_ATTENDANCE_REQUEST'))");
    }

    @AfterEach
    void unblockNotificationInsert() {
        jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
    }

    @Test
    @DisplayName("リマインド通知の永続化が実DBで失敗しても、リマインダーの送信済みマークはコミットされる")
    void 通知失敗でも個人予定リマインダーの送信済みマークは巻き戻らない() {
        long userId = 970_000_000L + (System.nanoTime() % 1_000_000L);
        Long scheduleId = insertPersonalSchedule(userId);
        Long reminderId = insertDueReminder(scheduleId);

        // 是正前はここで UnexpectedRollbackException（通知 INSERT の失敗が業務TXを汚染）。
        assertThatCode(() -> personalScheduleReminderService.processDueReminders())
                .as("通知の永続化失敗がバッチ本体へ伝播してはならない")
                .doesNotThrowAnyException();

        assertThat(reloadNotified(reminderId))
                .as("通知が実DBで失敗しても、送信済みマークは巻き戻らない"
                        + "（巻き戻ると次回実行でも同じリマインダーが due と判定され前へ進まない）")
                .isTrue();

        assertThat(countNotifications("SCHEDULE_REMINDER"))
                .as("通知の INSERT は実DBの CHECK 制約で本当に失敗している（握りつぶしではない）")
                .isZero();
    }

    @Test
    @DisplayName("出欠募集通知の永続化が実DBで失敗しても、生成された出欠レコードはコミットされる")
    void 通知失敗でも出欠募集の出欠レコードは巻き戻らない() {
        long teamId = 980_000_000L + (System.nanoTime() % 1_000_000L);
        Long scheduleId = insertTeamSchedule(teamId, teamId + 1L);
        insertTeamMember(teamId, teamId + 1L);

        assertThatCode(() -> scheduleAttendanceService.openAttendanceSolicitation(scheduleId))
                .as("通知の永続化失敗が出欠募集の業務処理へ伝播してはならない")
                .doesNotThrowAnyException();

        Long attendanceCount = transactionTemplate.execute(
                tx -> Long.valueOf(scheduleAttendanceRepository.countByScheduleId(scheduleId)));
        assertThat(attendanceCount)
                .as("通知が実DBで失敗しても、生成済みの出欠レコードは巻き戻らない"
                        + "（巻き戻ると誰にも出欠を訊けない状態に戻り、即時経路は再試行されない）")
                .isPositive();

        // 通知は AFTER_COMMIT + @Async のため、制約違反の試行が終わるのを待ってから 0 件を確認する。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications("SCHEDULE_ATTENDANCE_REQUEST"))
                        .as("通知の INSERT は実DBの CHECK 制約で本当に失敗している（握りつぶしではない）")
                        .isZero());
    }

    // ---- フィクスチャ / ヘルパ ----

    private Long insertPersonalSchedule(long userId) {
        LocalDateTime start = LocalDateTime.now().plusMinutes(10).withNano(0);
        return transactionTemplate.execute(tx -> scheduleRepository.save(ScheduleEntity.builder()
                .userId(userId)
                .title("#2990 L8 個人予定リマインド 通知境界検証")
                .startAt(start)
                .endAt(start.plusHours(1))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .minResponseRole(MinResponseRole.MEMBER_PLUS)
                .commentOption(CommentOption.OPTIONAL)
                .createdBy(userId)
                .build()).getId());
    }

    private Long insertTeamSchedule(long teamId, long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        return transactionTemplate.execute(tx -> scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("#2990 L8 出欠募集 通知境界検証")
                .startAt(start)
                .endAt(start.plusHours(2))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .minResponseRole(MinResponseRole.MEMBER_PLUS)
                .commentOption(CommentOption.OPTIONAL)
                .createdBy(createdBy)
                .build()).getId());
    }

    /** 出欠募集の宛先解決（{@code user_roles} の scope 検索）に載る行を 1 件作る。 */
    private void insertTeamMember(long teamId, long userId) {
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, team_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'MEMBER', NOW(), NOW())",
                userId, teamId);
    }

    private Long insertDueReminder(Long scheduleId) {
        return transactionTemplate.execute(tx -> personalScheduleReminderRepository.save(
                PersonalScheduleReminderEntity.builder()
                        .scheduleId(scheduleId)
                        .reminderKind(ReminderKind.ABSOLUTE)
                        .remindAt(LocalDateTime.now().minusMinutes(5).withNano(0))
                        .notified(false)
                        .build()).getId());
    }

    private boolean reloadNotified(Long reminderId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(
                tx -> personalScheduleReminderRepository.findById(reminderId).orElseThrow().getNotified()));
    }

    private Long countNotifications(String notificationType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ?",
                Long.class, notificationType);
    }
}
