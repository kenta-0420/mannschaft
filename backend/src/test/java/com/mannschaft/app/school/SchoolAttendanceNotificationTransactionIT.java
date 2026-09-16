package com.mannschaft.app.school;

import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.DailyRollCallEntry;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeRequest;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
import com.mannschaft.app.school.service.DailyAttendanceService;
import com.mannschaft.app.school.service.FamilyAttendanceNoticeService;
import com.mannschaft.app.school.service.SchoolAttendanceNotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L6 — school ドメインの通知トランザクション境界の実 DB 検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code DailyAttendanceService#submitDailyRollCall} と
 * {@code FamilyAttendanceNoticeService#submitNotice} / {@code #acknowledgeNotice} は
 * {@code @Transactional} の内側から {@link SchoolAttendanceNotificationService} を直接呼んでいた。
 * 同サービスは {@code @Transactional} を宣言していないため既定の {@code REQUIRED} で
 * 呼び出し元の業務トランザクションに参加する。通知側が例外を投げれば、そのまま業務トランザクションへ
 * 伝播して業務処理ごと巻き戻る。とくに {@code submitDailyRollCall} は通知呼び出しが
 * 生徒ごとのループの内側にあり try で囲われてもいないため、生徒 1 人ぶんの通知失敗で
 * <b>クラス全員ぶんの出欠行が全件</b>巻き戻っていた。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link SchoolAttendanceNotificationService} を spy して例外を投げさせ、業務行が
 * コミットされているかを別トランザクションで読み直す。是正前のコードでは通知の例外が
 * 業務メソッドの外まで伝播して呼び出し自体が失敗し（＝行も残らず）本テストは赤になる。
 * <b>赤くなる理由は「通知の失敗が業務トランザクションへ伝播している」ことそのもの</b>であり、
 * フィクスチャ不足や別の例外ではない。</p>
 *
 * <p>本 IT は {@code school.event} / {@code school.listener} の新規クラスを一切参照しない。
 * 是正前のコードに対してもそのままコンパイル・実行できるので、赤→緑を実測で比較できる。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むと
 * コミットが起きずリスナーが発火しないまま緑になる（偽の緑）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L6 school 出欠通知のトランザクション境界（実DB）")
class SchoolAttendanceNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private DailyAttendanceService dailyAttendanceService;

    @Autowired
    private FamilyAttendanceNoticeService familyAttendanceNoticeService;

    @Autowired
    private DailyAttendanceRecordRepository dailyAttendanceRecordRepository;

    @Autowired
    private FamilyAttendanceNoticeRepository familyAttendanceNoticeRepository;

    @Autowired
    private UserCareLinkRepository userCareLinkRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private SchoolAttendanceNotificationService notificationService;

    @Test
    @DisplayName("保護者通知が失敗しても、点呼した生徒全員の出欠記録はコミットされる")
    void 通知失敗でも点呼の出欠記録は全件コミットされる() {
        long teamId = 930_000_000L + (System.nanoTime() % 1_000_000L);
        long operatorId = teamId + 1L;
        long student1 = teamId + 11L;
        long student2 = teamId + 12L;
        long student3 = teamId + 13L;
        LocalDate date = LocalDate.of(2026, 5, 11);

        insertTeamMembership(operatorId, teamId);

        // 生徒 1 人目の保護者通知から失敗させる。是正前はここで業務TXごと落ちる。
        willThrow(new RuntimeException("模擬通知失敗（#2990 L6 検証用）"))
                .given(notificationService).notifyDailyAttendance(any(), any(), any());

        DailyRollCallRequest request = buildRollCallRequest(date, List.of(
                entry(student1, AttendanceStatus.ATTENDING),
                entry(student2, AttendanceStatus.ABSENT),
                entry(student3, AttendanceStatus.PARTIAL)));

        assertThatCode(() -> dailyAttendanceService.submitDailyRollCall(teamId, request, operatorId))
                .as("保護者通知の失敗が業務メソッドの外へ伝播してはならない")
                .doesNotThrowAnyException();

        Integer committed = transactionTemplate.execute(
                tx -> dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(teamId, date).size());
        assertThat(committed)
                .as("保護者通知が失敗しても点呼したクラス全員の出欠記録は巻き戻らない")
                .isEqualTo(3);

        awaitDailyDeliveryAttempted();
    }

    @Test
    @DisplayName("担任向け通知が失敗しても、保護者が送った欠席連絡はコミットされる")
    void 通知失敗でも保護者連絡はコミットされる() {
        long base = 940_000_000L + (System.nanoTime() % 1_000_000L);
        long teamId = base;
        long guardianId = base + 1L;
        long studentId = base + 2L;

        insertActiveCareLink(studentId, guardianId);

        willThrow(new RuntimeException("模擬通知失敗（#2990 L6 検証用）"))
                .given(notificationService).notifyFamilyNoticeSubmitted(any());

        FamilyAttendanceNoticeRequest req = FamilyAttendanceNoticeRequest.builder()
                .teamId(teamId)
                .studentUserId(studentId)
                .attendanceDate(LocalDate.of(2026, 5, 12))
                .noticeType(FamilyNoticeType.ABSENCE)
                .build();

        assertThatCode(() -> familyAttendanceNoticeService.submitNotice(guardianId, req))
                .as("担任向け通知の失敗が業務メソッドの外へ伝播してはならない")
                .doesNotThrowAnyException();

        boolean committed = Boolean.TRUE.equals(transactionTemplate.execute(
                tx -> !familyAttendanceNoticeRepository
                        .findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(
                                teamId, LocalDate.of(2026, 5, 12))
                        .isEmpty()));
        assertThat(committed)
                .as("担任向け通知が失敗しても保護者が送った連絡は巻き戻らない")
                .isTrue();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).notifyFamilyNoticeSubmitted(any()));
    }

    // ---- フィクスチャ / ヘルパ ----

    /** {@code checkMembership} を通すための memberships 行を張る。 */
    private void insertTeamMembership(long userId, long teamId) {
        transactionTemplate.executeWithoutResult(tx ->
                MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER));
    }

    /** {@code checkCareLink} を通すための ACTIVE なケアリンクを張る。 */
    private void insertActiveCareLink(long careRecipientUserId, long watcherUserId) {
        transactionTemplate.executeWithoutResult(tx -> userCareLinkRepository.save(
                UserCareLinkEntity.builder()
                        .careRecipientUserId(careRecipientUserId)
                        .watcherUserId(watcherUserId)
                        .careCategory(CareCategory.MINOR)
                        .status(CareLinkStatus.ACTIVE)
                        .invitedBy(CareLinkInvitedBy.WATCHER)
                        .notifyOnRsvp(true)
                        .notifyOnCheckin(true)
                        .createdBy(watcherUserId)
                        .build()));
    }

    private DailyRollCallRequest buildRollCallRequest(LocalDate date, List<DailyRollCallEntry> entries) {
        DailyRollCallRequest request = new DailyRollCallRequest();
        ReflectionTestUtils.setField(request, "attendanceDate", date);
        ReflectionTestUtils.setField(request, "entries", entries);
        return request;
    }

    private DailyRollCallEntry entry(long studentUserId, AttendanceStatus status) {
        DailyRollCallEntry e = new DailyRollCallEntry();
        ReflectionTestUtils.setField(e, "studentUserId", studentUserId);
        ReflectionTestUtils.setField(e, "status", status);
        return e;
    }

    /** AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。 */
    private void awaitDailyDeliveryAttempted() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).notifyDailyAttendance(any(), any(), any()));
    }
}
