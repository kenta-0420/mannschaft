package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.13 Phase 14: {@link AttendanceRequirementBatchService} 単体テスト。
 *
 * <p>日次評価バッチ・週次ダイジェストバッチの動作を Mockito で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceRequirementBatchService 単体テスト")
class AttendanceRequirementBatchServiceTest {

    @InjectMocks
    private AttendanceRequirementBatchService service;

    @Mock
    private AttendanceRequirementRuleRepository ruleRepository;

    @Mock
    private StudentAttendanceSummaryRepository summaryRepository;

    @Mock
    private AttendanceRequirementEvaluationService evaluationService;

    @Mock
    private AttendanceRequirementEvaluationRepository evaluationRepository;

    @Mock
    private ClassHomeroomRepository homeroomRepository;

    @Mock
    private SchoolAttendanceNotificationService notificationService;

    // ========================================
    // runDailyEvaluation
    // ========================================

    @Nested
    @DisplayName("runDailyEvaluation")
    class RunDailyEvaluation {

        @Test
        @DisplayName("正常系: ACTIVE規程があれば対象生徒の評価が呼ばれる")
        void evaluatesAllStudentsInActiveRules() {
            // Arrange
            AttendanceRequirementRuleEntity rule = buildRule(10L, null);
            StudentAttendanceSummaryEntity summary = buildSummary(200L, 10L);
            EvaluationResponse evalResp = buildEvaluationResponse(EvaluationStatus.OK);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(rule));
            given(summaryRepository.findClassSummaries(eq(10L), any(short.class)))
                    .willReturn(List.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    eq(200L), anyLong()))
                    .willReturn(Optional.empty());
            given(evaluationService.evaluate(eq(200L), anyLong()))
                    .willReturn(evalResp);

            // Act
            service.runDailyEvaluation();

            // Assert
            verify(evaluationService).evaluate(eq(200L), anyLong());
        }

        @Test
        @DisplayName("正常系: ステータスが OK→WARNING に変化したとき notifyRequirementWarning が呼ばれる")
        void notifiesOnStatusChange() {
            // Arrange
            AttendanceRequirementRuleEntity rule = buildRule(10L, null);
            StudentAttendanceSummaryEntity summary = buildSummary(200L, 10L);
            // 前回は OK
            AttendanceRequirementEvaluationEntity prevEval = buildEvalEntity(EvaluationStatus.OK);
            // 今回は WARNING
            EvaluationResponse newResp = buildEvaluationResponse(EvaluationStatus.WARNING);
            ClassHomeroomEntity homeroom = buildHomeroom(10L, 999L);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(rule));
            given(summaryRepository.findClassSummaries(eq(10L), any(short.class)))
                    .willReturn(List.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    eq(200L), anyLong()))
                    .willReturn(Optional.of(prevEval));
            given(evaluationService.evaluate(eq(200L), anyLong()))
                    .willReturn(newResp);
            given(homeroomRepository.findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
                    eq(10L), anyInt()))
                    .willReturn(Optional.of(homeroom));

            // Act
            service.runDailyEvaluation();

            // Assert: WARNING 通知が呼ばれること
            verify(notificationService).notifyRequirementWarning(eq(200L), any(), any());
        }

        @Test
        @DisplayName("正常系: 組織スコープ規程（teamId=null）はスキップされる")
        void skipsOrgScopedRules() {
            // Arrange: teamId=null の組織スコープ規程
            AttendanceRequirementRuleEntity orgRule = buildRule(null, 1L);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(orgRule));

            // Act
            service.runDailyEvaluation();

            // Assert: サマリー取得も評価も呼ばれない
            verify(summaryRepository, never()).findClassSummaries(anyLong(), any(short.class));
            verify(evaluationService, never()).evaluate(anyLong(), anyLong());
        }

        @Test
        @DisplayName("異常系: evaluate が例外をスローしても次の生徒に進む（ログエラー）")
        void continuesOnEvaluationError() {
            // Arrange
            AttendanceRequirementRuleEntity rule = buildRule(10L, null);
            // 2人の生徒
            StudentAttendanceSummaryEntity summary1 = buildSummary(200L, 10L);
            StudentAttendanceSummaryEntity summary2 = buildSummary(201L, 10L);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(rule));
            given(summaryRepository.findClassSummaries(eq(10L), any(short.class)))
                    .willReturn(List.of(summary1, summary2));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    anyLong(), anyLong()))
                    .willReturn(Optional.empty());
            // 1人目は例外、2人目は正常
            given(evaluationService.evaluate(eq(200L), anyLong()))
                    .willThrow(new RuntimeException("テスト用例外"));
            given(evaluationService.evaluate(eq(201L), anyLong()))
                    .willReturn(buildEvaluationResponse(EvaluationStatus.OK));

            // Act（例外がスローされないことを確認）
            service.runDailyEvaluation();

            // Assert: 2人目の評価も実行される
            verify(evaluationService).evaluate(eq(200L), anyLong());
            verify(evaluationService).evaluate(eq(201L), anyLong());
        }
    }

    // ========================================
    // sendWeeklyDigest
    // ========================================

    @Nested
    @DisplayName("sendWeeklyDigest")
    class SendWeeklyDigest {

        @Test
        @DisplayName("正常系: RISK生徒ありかつ担任ありのとき sendWeeklyRiskDigest が呼ばれる")
        void sendsDigestToHomeroomTeacher() {
            // Arrange
            AttendanceRequirementRuleEntity rule = buildRule(10L, null);
            AttendanceRequirementEvaluationEntity atRiskEval = buildEvalEntity(EvaluationStatus.RISK);
            ClassHomeroomEntity homeroom = buildHomeroom(10L, 999L);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(rule));
            given(evaluationRepository.findAtRiskByTeamId(eq(10L), any()))
                    .willReturn(List.of(atRiskEval));
            given(homeroomRepository.findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
                    eq(10L), anyInt()))
                    .willReturn(Optional.of(homeroom));

            // Act
            service.sendWeeklyDigest();

            // Assert
            verify(notificationService).sendWeeklyRiskDigest(eq(10L), eq(1), eq(999L));
        }

        @Test
        @DisplayName("正常系: リスク生徒がいないチームは sendWeeklyRiskDigest が呼ばれない")
        void skipsTeamWithNoAtRiskStudents() {
            // Arrange
            AttendanceRequirementRuleEntity rule = buildRule(10L, null);

            given(ruleRepository.findAllActive(any(LocalDate.class), any(short.class)))
                    .willReturn(List.of(rule));
            given(evaluationRepository.findAtRiskByTeamId(eq(10L), any()))
                    .willReturn(List.of());  // リスク生徒なし

            // Act
            service.sendWeeklyDigest();

            // Assert
            verify(notificationService, never()).sendWeeklyRiskDigest(anyLong(), anyInt(), anyLong());
        }
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * テスト用の要件規程エンティティを構築する。
     *
     * @param teamId 規程のチームID（null の場合は組織スコープ規程）
     * @param ruleId 規程ID（ReflectionTestUtils で設定）
     */
    private AttendanceRequirementRuleEntity buildRule(Long teamId, Long ruleId) {
        AttendanceRequirementRuleEntity rule = AttendanceRequirementRuleEntity.builder()
                .teamId(teamId)
                .academicYear((short) 2026)
                .name("テスト規程")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .build();
        if (ruleId != null) {
            ReflectionTestUtils.setField(rule, "id", ruleId);
        } else {
            ReflectionTestUtils.setField(rule, "id", 1L);
        }
        return rule;
    }

    /**
     * テスト用の出席集計エンティティを構築する。
     *
     * @param studentUserId 生徒ユーザーID
     * @param teamId        チームID
     */
    private StudentAttendanceSummaryEntity buildSummary(Long studentUserId, Long teamId) {
        StudentAttendanceSummaryEntity summary = StudentAttendanceSummaryEntity.builder()
                .teamId(teamId)
                .studentUserId(studentUserId)
                .academicYear((short) 2026)
                .termId(null)
                .periodFrom(LocalDate.of(2026, 4, 1))
                .periodTo(LocalDate.of(2027, 3, 31))
                .totalSchoolDays((short) 100)
                .absentDays((short) 10)
                .build();
        ReflectionTestUtils.setField(summary, "id", studentUserId * 10L);
        return summary;
    }

    /**
     * テスト用の評価エンティティを構築する。
     *
     * @param status 評価ステータス
     */
    private AttendanceRequirementEvaluationEntity buildEvalEntity(EvaluationStatus status) {
        AttendanceRequirementEvaluationEntity eval = AttendanceRequirementEvaluationEntity.builder()
                .requirementRuleId(1L)
                .studentUserId(200L)
                .summaryId(2000L)
                .status(status)
                .currentAttendanceRate(new BigDecimal("90.00"))
                .remainingAllowedAbsences(10)
                .evaluatedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(eval, "id", 100L);
        return eval;
    }

    /**
     * テスト用の EvaluationResponse を構築する。
     *
     * @param status 評価ステータス
     */
    private EvaluationResponse buildEvaluationResponse(EvaluationStatus status) {
        return new EvaluationResponse(
                100L, 1L, 200L, 2000L,
                status,
                new BigDecimal("90.00"),
                10,
                LocalDateTime.now(),
                null, null, null);
    }

    /**
     * テスト用の学級担任エンティティを構築する。
     *
     * @param teamId                チームID
     * @param homeroomTeacherUserId 担任ユーザーID
     */
    private ClassHomeroomEntity buildHomeroom(Long teamId, Long homeroomTeacherUserId) {
        ClassHomeroomEntity homeroom = ClassHomeroomEntity.builder()
                .teamId(teamId)
                .homeroomTeacherUserId(homeroomTeacherUserId)
                .academicYear(2026)
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .createdBy(1L)
                .build();
        ReflectionTestUtils.setField(homeroom, "id", 50L);
        return homeroom;
    }
}
