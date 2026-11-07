package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.DailyAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.DailyRollCallEntry;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceEntry;
import com.mannschaft.app.school.dto.PeriodAttendanceRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.RecalculateSummaryRequest;
import com.mannschaft.app.school.error.SchoolErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 日次・時限・保護者連絡・統計・集計の各 Facade の認可テスト。
 *
 * <p>共通の契約: 認可（と入力の在籍確認）を<b>業務 Service より前に</b>行い、拒否されたら業務 Service
 * （トランザクション）へ一切進まない。認可はトランザクションの外で行う（D-3T）。</p>
 */
@DisplayName("学校出欠 Facade 認可テスト")
class SchoolAttendanceFacadesAuthzTest {

    private static final Long TEAM_ID = 1L;
    private static final Long OPERATOR = 100L;
    private static final Long OUTSIDER = 999L;
    private static final Long STUDENT = 201L;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 30);

    private static BusinessException forbidden() {
        return new BusinessException(CommonErrorCode.COMMON_002);
    }

    // ========================================
    // 日次出欠
    // ========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("DailyAttendanceFacade")
    class Daily {

        @Mock
        private DailyAttendanceService service;

        @Mock
        private SchoolAttendanceAccessPolicy policy;

        @InjectMocks
        private DailyAttendanceFacade facade;

        private DailyRollCallRequest request() {
            DailyRollCallEntry entry = new DailyRollCallEntry();
            ReflectionTestUtils.setField(entry, "studentUserId", STUDENT);
            ReflectionTestUtils.setField(entry, "status", AttendanceStatus.ABSENT);
            DailyRollCallRequest request = new DailyRollCallRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));
            return request;
        }

        @Test
        @DisplayName("R でない操作者は 403 で、在籍確認も業務 Service も走らない")
        void roll_forbidden_noSideEffects() {
            doThrow(forbidden()).when(policy).checkCanRecordDaily(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.submitDailyRollCall(TEAM_ID, request(), OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verify(policy, never()).requireEnrolledStudents(any(), any());
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("在籍でない生徒が混ざると例外で全件拒否され、業務 Service は呼ばれない")
        void roll_notEnrolled_rejected() {
            BusinessException notEnrolled = new BusinessException(SchoolErrorCode.STUDENT_NOT_ENROLLED);
            doThrow(notEnrolled).when(policy).requireEnrolledStudents(any(), any());

            assertThatThrownBy(() -> facade.submitDailyRollCall(TEAM_ID, request(), OPERATOR))
                    .isSameAs(notEnrolled);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("認可 → 在籍確認 → 登録の順で委譲する")
        void roll_orderedDelegation() {
            DailyRollCallRequest request = request();

            facade.submitDailyRollCall(TEAM_ID, request, OPERATOR);

            InOrder inOrder = Mockito.inOrder(policy, service);
            inOrder.verify(policy).checkCanRecordDaily(OPERATOR, TEAM_ID);
            inOrder.verify(policy).requireEnrolledStudents(any(), any());
            inOrder.verify(service).submitDailyRollCall(TEAM_ID, request, OPERATOR);
        }

        @Test
        @DisplayName("V の無いユーザーの一覧取得は 403、業務 Service は呼ばれない")
        void list_nonViewer_forbidden() {
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getDailyAttendance(TEAM_ID, DATE, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("R の無い者の個別修正は 403、業務 Service は呼ばれない")
        void update_nonWriter_forbidden() {
            doThrow(forbidden()).when(policy).checkCanRecordDaily(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.updateDailyRecord(
                    TEAM_ID, 1L, new DailyAttendanceUpdateRequest(), OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }
    }

    // ========================================
    // 時限出欠
    // ========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("PeriodAttendanceFacade")
    class Period {

        @Mock
        private PeriodAttendanceService service;

        @Mock
        private SchoolAttendanceAccessPolicy policy;

        @InjectMocks
        private PeriodAttendanceFacade facade;

        private PeriodAttendanceRequest request() {
            PeriodAttendanceEntry entry = new PeriodAttendanceEntry();
            ReflectionTestUtils.setField(entry, "studentUserId", STUDENT);
            ReflectionTestUtils.setField(entry, "status", AttendanceStatus.ABSENT);
            PeriodAttendanceRequest request = new PeriodAttendanceRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));
            return request;
        }

        @Test
        @DisplayName("P でない操作者は 403 で、行の作成も移動検知も在籍確認も走らない")
        void submit_forbidden_noSideEffects() {
            doThrow(forbidden()).when(policy).checkCanRecordPeriod(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.submitPeriodAttendance(TEAM_ID, 2, request(), OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verify(policy, never()).requireEnrolledStudents(any(), any());
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("在籍でない生徒が混ざると全件拒否され、業務 Service は呼ばれない")
        void submit_notEnrolled_rejected() {
            BusinessException notEnrolled = new BusinessException(SchoolErrorCode.STUDENT_NOT_ENROLLED);
            doThrow(notEnrolled).when(policy).requireEnrolledStudents(any(), any());

            assertThatThrownBy(() -> facade.submitPeriodAttendance(TEAM_ID, 2, request(), OPERATOR))
                    .isSameAs(notEnrolled);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("V の無いユーザーの一覧・候補取得は 403、業務 Service は呼ばれない")
        void read_nonViewer_forbidden() {
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getPeriodAttendance(TEAM_ID, DATE, 2, OUTSIDER))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> facade.getPeriodCandidates(TEAM_ID, DATE, 2, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("P の無い者の個別修正は 403、業務 Service は呼ばれない")
        void update_nonWriter_forbidden() {
            doThrow(forbidden()).when(policy).checkCanRecordPeriod(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.updatePeriodRecord(
                    TEAM_ID, 1L, new PeriodAttendanceUpdateRequest(), OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }
    }

    // ========================================
    // 保護者の出欠連絡（教職員側）
    // ========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("FamilyAttendanceNoticeFacade")
    class Family {

        private static final Long NOTICE_ID = 5L;

        @Mock
        private FamilyAttendanceNoticeService service;

        @Mock
        private SchoolAttendanceAccessPolicy policy;

        @InjectMocks
        private FamilyAttendanceNoticeFacade facade;

        @Test
        @DisplayName("AC-14: R の無い者（委任者・一般 MEMBER）は確認・反映できず 403、業務処理は走らない")
        void nonWriter_forbidden() {
            doThrow(forbidden()).when(policy).checkCanRecordDaily(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.acknowledgeNotice(TEAM_ID, NOTICE_ID, OUTSIDER))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> facade.applyToAttendanceRecord(TEAM_ID, NOTICE_ID, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verify(service, never()).acknowledgeNotice(any(), any(), any());
            verify(service, never()).applyToAttendanceRecord(any(), any(), any());
        }

        @Test
        @DisplayName("path の teamId 配下でない連絡は認可判定に到達せず 404（存在秘匿）")
        void otherTeamNotice_hiddenBeforeAuthz() {
            doThrow(new BusinessException(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND))
                    .when(service).requireNoticeInTeam(TEAM_ID, NOTICE_ID);

            assertThatThrownBy(() -> facade.acknowledgeNotice(TEAM_ID, NOTICE_ID, OPERATOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND);

            verifyNoInteractions(policy);
        }

        @Test
        @DisplayName("R を持つ者は、スコープ照合 → 認可 → 確認の順で確認できる")
        void writer_orderedDelegation() {
            facade.acknowledgeNotice(TEAM_ID, NOTICE_ID, OPERATOR);

            InOrder inOrder = Mockito.inOrder(service, policy);
            inOrder.verify(service).requireNoticeInTeam(TEAM_ID, NOTICE_ID);
            inOrder.verify(policy).checkCanRecordDaily(OPERATOR, TEAM_ID);
            inOrder.verify(service).acknowledgeNotice(TEAM_ID, NOTICE_ID, OPERATOR);
        }

        @Test
        @DisplayName("AC-14: V の無い者の一覧取得は 403、業務 Service は呼ばれない")
        void list_nonViewer_forbidden() {
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getTeamNotices(TEAM_ID, DATE, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }
    }

    // ========================================
    // 統計・CSV
    // ========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("AttendanceStatisticsFacade")
    class Statistics {

        @Mock
        private AttendanceStatisticsService service;

        @Mock
        private SchoolAttendanceAccessPolicy policy;

        @InjectMocks
        private AttendanceStatisticsFacade facade;

        @Test
        @DisplayName("V の無いユーザーの月次集計・CSV は 403、業務 Service は呼ばれない")
        void nonViewer_forbidden() {
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getMonthlyStatistics(TEAM_ID, 2026, 5, OUTSIDER))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> facade.exportAttendanceCsv(TEAM_ID, DATE, DATE, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("V を持つユーザーは月次集計・CSV を取得できる")
        void viewer_delegates() {
            facade.getMonthlyStatistics(TEAM_ID, 2026, 5, OPERATOR);
            facade.exportAttendanceCsv(TEAM_ID, DATE, DATE, OPERATOR);

            verify(service).getMonthlyStatistics(TEAM_ID, 2026, 5);
            verify(service).exportAttendanceCsv(TEAM_ID, DATE, DATE);
        }
    }

    // ========================================
    // 出席集計
    // ========================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @DisplayName("AttendanceSummaryFacade")
    class Summary {

        @Mock
        private AttendanceSummaryService service;

        @Mock
        private SchoolAttendanceAccessPolicy policy;

        @InjectMocks
        private AttendanceSummaryFacade facade;

        private RecalculateSummaryRequest recalc() {
            RecalculateSummaryRequest request = new RecalculateSummaryRequest();
            ReflectionTestUtils.setField(request, "teamId", TEAM_ID);
            return request;
        }

        @Test
        @DisplayName("AC-4: 本人・保護者は V の判定をせずに自分の生徒の集計を取得できる")
        void selfOrGuardian_skipsViewCheck() {
            given(policy.isSelfOrGuardian(STUDENT, OPERATOR)).willReturn(true);

            facade.getStudentSummary(STUDENT, TEAM_ID, (short) 2026, null, OPERATOR);

            verify(policy, never()).checkCanView(any(), any());
            verify(service).getStudentSummary(STUDENT, TEAM_ID, (short) 2026, null);
        }

        @Test
        @DisplayName("AC-4: 本人でも保護者でもない者は V が必要で、無ければ 403、業務 Service は呼ばれない")
        void other_requiresView() {
            given(policy.isSelfOrGuardian(STUDENT, OUTSIDER)).willReturn(false);
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getStudentSummary(STUDENT, TEAM_ID, (short) 2026, null, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("V の無いユーザーのクラス集計一覧は 403")
        void classList_nonViewer_forbidden() {
            doThrow(forbidden()).when(policy).checkCanView(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.getClassSummaries(TEAM_ID, (short) 2026, null, OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("AC-13: R の無い者の再計算は 403、業務 Service は呼ばれない")
        void recalc_nonWriter_forbidden() {
            doThrow(forbidden()).when(policy).checkCanRecordDaily(OUTSIDER, TEAM_ID);

            assertThatThrownBy(() -> facade.recalculate(STUDENT, recalc(), OUTSIDER))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("AC-13: 対象生徒が在籍でなければ SUMMARY_NOT_FOUND、業務 Service は呼ばれない")
        void recalc_notEnrolled_notFound() {
            given(policy.isEnrolledStudent(TEAM_ID, STUDENT)).willReturn(false);

            assertThatThrownBy(() -> facade.recalculate(STUDENT, recalc(), OPERATOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.SUMMARY_NOT_FOUND);

            verifyNoInteractions(service);
        }
    }
}
