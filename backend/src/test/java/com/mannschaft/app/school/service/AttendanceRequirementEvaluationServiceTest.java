package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.dto.ResolveEvaluationRequest;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F03.13 Phase 12: {@link AttendanceRequirementEvaluationService} 単体テスト。
 *
 * <p>評価ロジック（ステータス判定・欠席換算・残余許容日数計算）および
 * 違反解消フローを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceRequirementEvaluationService 単体テスト")
class AttendanceRequirementEvaluationServiceTest {

    @InjectMocks
    private AttendanceRequirementEvaluationService service;

    @Mock
    private AttendanceRequirementEvaluationRepository evaluationRepository;

    @Mock
    private AttendanceRequirementRuleRepository ruleRepository;

    @Mock
    private StudentAttendanceSummaryRepository summaryRepository;

    // ========================================
    // evaluate
    // ========================================

    @Nested
    @DisplayName("evaluate — ステータス判定")
    class Evaluate {

        @Test
        @DisplayName("正常系: 出席率が warningThresholdRate 以上なら OK")
        void 出席率がwarningThresholdRate以上ならOK() {
            // rule: minAttendanceRate=80, warningThresholdRate=85
            AttendanceRequirementRuleEntity rule = buildRule(
                    new BigDecimal("80"), null, new BigDecimal("85"));
            // summary: totalSchoolDays=100, absentDays=10 → effectiveAbsent=10, rate=90
            StudentAttendanceSummaryEntity summary = buildSummary(100, 10, 0, 0, 0, 0, (short) 0);

            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    any(), any())).willReturn(Optional.empty());
            given(evaluationRepository.save(any())).willAnswer(inv -> {
                AttendanceRequirementEvaluationEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 100L);
                return e;
            });

            EvaluationResponse result = service.evaluate(200L, 1L);

            assertThat(result.status()).isEqualTo(EvaluationStatus.OK);
            // 出席率 = (100 - 10) / 100 * 100 = 90.00
            assertThat(result.currentAttendanceRate()).isEqualByComparingTo(new BigDecimal("90.00"));
        }

        @Test
        @DisplayName("正常系: 出席率が warningThresholdRate 未満かつ minAttendanceRate 以上なら WARNING")
        void 出席率がwarningThresholdRate未満でminAttendanceRate以上ならWARNING() {
            // rule: minAttendanceRate=80, warningThresholdRate=85
            AttendanceRequirementRuleEntity rule = buildRule(
                    new BigDecimal("80"), null, new BigDecimal("85"));
            // summary: totalSchoolDays=100, absentDays=18 → rate=82
            StudentAttendanceSummaryEntity summary = buildSummary(100, 18, 0, 0, 0, 0, (short) 0);

            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    any(), any())).willReturn(Optional.empty());
            given(evaluationRepository.save(any())).willAnswer(inv -> {
                AttendanceRequirementEvaluationEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 100L);
                return e;
            });

            EvaluationResponse result = service.evaluate(200L, 1L);

            assertThat(result.status()).isEqualTo(EvaluationStatus.WARNING);
            // 出席率 = (100 - 18) / 100 * 100 = 82.00
            assertThat(result.currentAttendanceRate()).isEqualByComparingTo(new BigDecimal("82.00"));
        }

        @Test
        @DisplayName("正常系: 出席率が minAttendanceRate 未満かつ残余あり → RISK")
        void 出席率がminAttendanceRate未満で残余ありならRISK() {
            // rule: minAttendanceRate=80, maxAbsenceDays=30
            AttendanceRequirementRuleEntity rule = buildRule(
                    new BigDecimal("80"), (short) 30, null);
            // summary: totalSchoolDays=100, absentDays=25 → rate=75, remaining=30-25=5
            StudentAttendanceSummaryEntity summary = buildSummary(100, 25, 0, 0, 0, 0, (short) 0);

            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    any(), any())).willReturn(Optional.empty());
            given(evaluationRepository.save(any())).willAnswer(inv -> {
                AttendanceRequirementEvaluationEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 100L);
                return e;
            });

            EvaluationResponse result = service.evaluate(200L, 1L);

            assertThat(result.status()).isEqualTo(EvaluationStatus.RISK);
            assertThat(result.remainingAllowedAbsences()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系: maxAbsenceDays 超過 → VIOLATION")
        void maxAbsenceDays超過ならVIOLATION() {
            // rule: maxAbsenceDays=20（minAttendanceRate なし）
            AttendanceRequirementRuleEntity rule = buildRule(null, (short) 20, null);
            // summary: absentDays=25 → 超過
            StudentAttendanceSummaryEntity summary = buildSummary(100, 25, 0, 0, 0, 0, (short) 0);

            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    any(), any())).willReturn(Optional.empty());
            given(evaluationRepository.save(any())).willAnswer(inv -> {
                AttendanceRequirementEvaluationEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 100L);
                return e;
            });

            EvaluationResponse result = service.evaluate(200L, 1L);

            assertThat(result.status()).isEqualTo(EvaluationStatus.VIOLATION);
        }

        @Test
        @DisplayName("正常系: countSickBayAsPresent=true のとき sickBayDays は欠席から除外される")
        void countSickBayAsPresentがtrueのときsickBayDaysは除外される() {
            // rule: minAttendanceRate=80, countSickBayAsPresent=true（デフォルト）
            AttendanceRequirementRuleEntity rule = buildRule(
                    new BigDecimal("80"), null, null);
            // summary: totalSchoolDays=100, absentDays=25, sickBayDays=10
            // effectiveAbsent = 25 - 10 = 15 → rate = 85.00
            StudentAttendanceSummaryEntity summary = buildSummary(100, 25, 10, 0, 0, 0, (short) 0);

            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.of(summary));
            given(evaluationRepository.findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
                    any(), any())).willReturn(Optional.empty());
            given(evaluationRepository.save(any())).willAnswer(inv -> {
                AttendanceRequirementEvaluationEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 100L);
                return e;
            });

            EvaluationResponse result = service.evaluate(200L, 1L);

            // warningThresholdRate が null の場合、minAttendanceRate(80) 以上 → OK
            assertThat(result.status()).isEqualTo(EvaluationStatus.OK);
            assertThat(result.currentAttendanceRate()).isEqualByComparingTo(new BigDecimal("85.00"));
        }

        @Test
        @DisplayName("異常系: 規程が存在しない → REQUIREMENT_RULE_NOT_FOUND")
        void 規程が存在しないならREQUIREMENT_RULE_NOT_FOUND() {
            given(ruleRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.evaluate(200L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常系: 集計が存在しない → SUMMARY_NOT_FOUND")
        void 集計が存在しないならSUMMARY_NOT_FOUND() {
            AttendanceRequirementRuleEntity rule = buildRule(new BigDecimal("80"), null, null);
            given(ruleRepository.findById(1L)).willReturn(Optional.of(rule));
            given(summaryRepository.findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
                    any(), any(), any(short.class), any()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.evaluate(200L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.SUMMARY_NOT_FOUND.getMessage());
        }
    }

    // ========================================
    // resolveViolation
    // ========================================

    @Nested
    @DisplayName("resolveViolation — 違反解消")
    class ResolveViolation {

        @Test
        @DisplayName("正常系: 未解消の評価を解消できる")
        void 未解消の評価を解消できる() {
            // 未解消の評価エンティティ
            AttendanceRequirementEvaluationEntity entity = AttendanceRequirementEvaluationEntity.builder()
                    .requirementRuleId(1L)
                    .studentUserId(200L)
                    .summaryId(10L)
                    .status(EvaluationStatus.VIOLATION)
                    .currentAttendanceRate(new BigDecimal("75.00"))
                    .remainingAllowedAbsences(0)
                    .evaluatedAt(LocalDateTime.now().minusDays(1))
                    .build();
            ReflectionTestUtils.setField(entity, "id", 50L);

            given(evaluationRepository.findById(50L)).willReturn(Optional.of(entity));
            given(evaluationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ResolveEvaluationRequest request = new ResolveEvaluationRequest("保護者と面談し指導完了");
            EvaluationResponse result = service.resolveViolation(50L, 999L, request);

            // 解消済みになっていることを確認
            assertThat(result.resolutionNote()).isEqualTo("保護者と面談し指導完了");
            assertThat(result.resolverUserId()).isEqualTo(999L);
            assertThat(result.resolvedAt()).isNotNull();
            verify(evaluationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 評価が存在しない → EVALUATION_NOT_FOUND")
        void 評価が存在しないならEVALUATION_NOT_FOUND() {
            given(evaluationRepository.findById(999L)).willReturn(Optional.empty());

            ResolveEvaluationRequest request = new ResolveEvaluationRequest("解消理由");
            assertThatThrownBy(() -> service.resolveViolation(999L, 1L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常系: 既に解消済みの評価 → EVALUATION_ALREADY_RESOLVED")
        void 既に解消済みの評価ならEVALUATION_ALREADY_RESOLVED() {
            // 解消済みの評価エンティティ（resolvedAt が設定済み）
            AttendanceRequirementEvaluationEntity entity = AttendanceRequirementEvaluationEntity.builder()
                    .requirementRuleId(1L)
                    .studentUserId(200L)
                    .summaryId(10L)
                    .status(EvaluationStatus.VIOLATION)
                    .currentAttendanceRate(new BigDecimal("75.00"))
                    .remainingAllowedAbsences(0)
                    .evaluatedAt(LocalDateTime.now().minusDays(1))
                    .build();
            ReflectionTestUtils.setField(entity, "id", 50L);
            // 既に resolve 済みにする
            entity.resolve(888L, "既存の解消理由");

            given(evaluationRepository.findById(50L)).willReturn(Optional.of(entity));

            ResolveEvaluationRequest request = new ResolveEvaluationRequest("再解消しようとする");
            assertThatThrownBy(() -> service.resolveViolation(50L, 999L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_ALREADY_RESOLVED.getMessage());
        }
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * テスト用の要件規程エンティティを構築する。
     * countSickBayAsPresent/countSeparateRoomAsPresent/countOnlineAsPresent は true（デフォルト）。
     */
    private AttendanceRequirementRuleEntity buildRule(
            BigDecimal minAttendanceRate,
            Short maxAbsenceDays,
            BigDecimal warningThresholdRate) {

        return AttendanceRequirementRuleEntity.builder()
                .teamId(10L)
                .academicYear((short) 2026)
                .termId(null)
                .name("テスト規程")
                .minAttendanceRate(minAttendanceRate)
                .maxAbsenceDays(maxAbsenceDays)
                .warningThresholdRate(warningThresholdRate)
                // 換算フラグはデフォルト値（@Builder.Default）を使用
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .build();
    }

    /**
     * テスト用の出席集計エンティティを構築する。
     *
     * @param totalSchoolDays 授業日数
     * @param absentDays      欠席日数
     * @param sickBayDays     保健室登校日数
     * @param separateRoomDays 別室登校日数
     * @param onlineDays      オンライン登校日数
     * @param homeLearningDays 家庭学習日数
     * @param lateCount       遅刻回数
     */
    private StudentAttendanceSummaryEntity buildSummary(
            int totalSchoolDays,
            int absentDays,
            int sickBayDays,
            int separateRoomDays,
            int onlineDays,
            int homeLearningDays,
            short lateCount) {

        return StudentAttendanceSummaryEntity.builder()
                .teamId(10L)
                .studentUserId(200L)
                .academicYear((short) 2026)
                .termId(null)
                .periodFrom(LocalDate.of(2026, 4, 1))
                .periodTo(LocalDate.of(2026, 3, 31))
                .totalSchoolDays((short) totalSchoolDays)
                .absentDays((short) absentDays)
                .sickBayDays((short) sickBayDays)
                .separateRoomDays((short) separateRoomDays)
                .onlineDays((short) onlineDays)
                .homeLearningDays((short) homeLearningDays)
                .lateCount(lateCount)
                .build();
    }
}
