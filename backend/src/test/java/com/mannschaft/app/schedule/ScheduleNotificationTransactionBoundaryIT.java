package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * <p>本ロットで是正した経路のうちリマインド／出欠募集は
 * {@code NotificationHelper#notifyAllPreAuthorized} → {@code NotificationBulkFanoutService#insertAndDispatchChunk}
 * を通り、そこでチャンク失敗が {@code try/catch} で握られる。spy に {@code RuntimeException} を
 * 投げさせても catch に握られて終わり、テストは何も検証しないまま緑になる（＝偽の緑）。
 * そこで {@code notifications} テーブルへテスト側で CHECK 制約を張り、対象種別の INSERT だけを
 * <b>実 DB で失敗させる</b>。制約は {@code @AfterEach} で必ず落とすため他テストに影響しない。
 * CHECK 制約の付与・削除は MySQL の DDL であり暗黙コミットを伴うため、トランザクション外
 * （{@code @BeforeEach} / {@code @AfterEach}）で実行する
 * （手法は {@code NotificationCreditFreeQuotaAlertTransactionIT} と同型）。</p>
 *
 * <h2>是正前の実測結果（見立てとの食い違いを隠さず残す）</h2>
 * <p>2026-09-05、是正前のコード（{@code ScheduleReminderNotificationListener} が素の
 * {@code @EventListener}）へリマインドのテストを当てて実測した: <b>緑だった</b>
 * （tests=2 / failures=1 のうち失敗は出欠募集側のフィクスチャ不備で、リマインドは PASS）。
 * 通知は CHECK 制約で 0 件のまま、{@code markAsNotified} はコミットされていた。
 * バルク INSERT が JPA の永続化コンテキストを経由しないため rollback-only が立たず、
 * {@code notifyAllPreAuthorized} の一括 catch がこの経路では実際に効いていたためである。</p>
 *
 * <p>つまり<b>リマインド経路の実害は巻き戻りではなく順序（因果）であり、本テストは
 * 「巻き戻りが起きないこと」を将来にわたって固定する回帰テストである</b>——是正前の欠陥を
 * 再現する red テストではない。この区別を曖昧にすると「赤くなったから直った」という
 * 誤った因果を残すので、実測のとおりに書いておく。順序そのもの（業務コミット後にのみ
 * 通知が走ること）は {@code @TransactionalEventListener(AFTER_COMMIT)} の宣言と、
 * それを機械検証する {@code NotificationTransactionBoundaryGuardTest} の凍結台帳が担保する。</p>
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

    @PersistenceContext
    private EntityManager em;

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
        String nonce = String.valueOf(System.nanoTime());
        long teamId = 980_000_000L + (System.nanoTime() % 1_000_000L);
        Long memberId = insertTeamMember(teamId, nonce);
        Long scheduleId = insertTeamSchedule(teamId, memberId);

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

    /**
     * 出欠募集の宛先解決に載るメンバーを 1 名作る。
     *
     * <p>{@code UserRoleRepository#findUserIdsByScope} は {@code user_roles} と {@code memberships} の
     * <b>和集合</b>を取り、さらに {@code users}（{@code deleted_at IS NULL} かつ {@code status='ACTIVE'}）へ
     * JOIN する。したがって実在する users 行が要る（ID を捏造した行だけでは JOIN で落ちて
     * 「対象0名」になり、テストが何も検証しないまま緑になる）。</p>
     */
    private Long insertTeamMember(long teamId, String nonce) {
        return transactionTemplate.execute(tx -> {
            Long userId = ScheduleCommentTestFixtures.insertUser(
                    em, "l8-att-" + nonce + "@example.com", "L8 出欠対象");
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.flush();
            return userId;
        });
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
