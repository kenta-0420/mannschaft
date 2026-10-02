package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private SchoolAttendanceAccessPolicy policy;

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

            EvaluationResponse result = service.evaluateInternal(200L, 1L);

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

            EvaluationResponse result = service.evaluateInternal(200L, 1L);

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

            EvaluationResponse result = service.evaluateInternal(200L, 1L);

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

            EvaluationResponse result = service.evaluateInternal(200L, 1L);

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

            EvaluationResponse result = service.evaluateInternal(200L, 1L);

            // warningThresholdRate が null の場合、minAttendanceRate(80) 以上 → OK
            assertThat(result.status()).isEqualTo(EvaluationStatus.OK);
            assertThat(result.currentAttendanceRate()).isEqualByComparingTo(new BigDecimal("85.00"));
        }

        @Test
        @DisplayName("異常系: 規程が存在しない → REQUIREMENT_RULE_NOT_FOUND")
        void 規程が存在しないならREQUIREMENT_RULE_NOT_FOUND() {
            given(ruleRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.evaluateInternal(200L, 999L))
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

            assertThatThrownBy(() -> service.evaluateInternal(200L, 1L))
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
            given(ruleRepository.findById(1L)).willReturn(Optional.of(buildRule(null, null, null)));
            given(policy.canRecordDaily(999L, 10L)).willReturn(true);
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
            given(ruleRepository.findById(1L)).willReturn(Optional.of(buildRule(null, null, null)));
            given(policy.canRecordDaily(999L, 10L)).willReturn(true);

            ResolveEvaluationRequest request = new ResolveEvaluationRequest("再解消しようとする");
            assertThatThrownBy(() -> service.resolveViolation(50L, 999L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_ALREADY_RESOLVED.getMessage());
        }

        @Test
        @DisplayName("認可: 日次登録権（R）の無い者は 404（EVALUATION_NOT_FOUND・存在秘匿）")
        void 非メンバーはEVALUATION_NOT_FOUND() {
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
            given(ruleRepository.findById(1L)).willReturn(Optional.of(buildRule(null, null, null)));
            given(policy.canRecordDaily(777L, 10L)).willReturn(false);

            ResolveEvaluationRequest request = new ResolveEvaluationRequest("越境で解消しようとする");
            assertThatThrownBy(() -> service.resolveViolation(50L, 777L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_NOT_FOUND.getMessage());
        }
    }

    // ========================================
    // evaluate（HTTP 公開入口・認可あり）
    // ========================================

    @Nested
    @DisplayName("evaluate — 公開入口の認可")
    class EvaluateAuthorization {

        private AttendanceRequirementRuleEntity orgRule() {
            return AttendanceRequirementRuleEntity.builder()
                    .organizationId(5L)
                    .academicYear((short) 2026)
                    .name("組織規程")
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .build();
        }

        @Test
        @DisplayName("認可: 日次登録権（R）の無い者は 404（REQUIREMENT_RULE_NOT_FOUND・存在秘匿）")
        void 書込権なしはREQUIREMENT_RULE_NOT_FOUND() {
            given(ruleRepository.findById(1L)).willReturn(Optional.of(buildRule(null, null, null)));
            given(policy.canRecordDaily(777L, 10L)).willReturn(false);

            assertThatThrownBy(() -> service.evaluate(200L, 1L, 777L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("AC-13: R を持つが対象生徒がクラスの在籍メンバーでなければ 4xx（SUMMARY_NOT_FOUND）")
        void 非在籍生徒はSUMMARY_NOT_FOUND() {
            given(ruleRepository.findById(1L)).willReturn(Optional.of(buildRule(null, null, null)));
            given(policy.canRecordDaily(999L, 10L)).willReturn(true);
            given(accessControlService.listActiveMemberIds(10L, "TEAM")).willReturn(java.util.List.of(201L));

            assertThatThrownBy(() -> service.evaluate(200L, 1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.SUMMARY_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("AC-22: 組織スコープ規程は組織の一般 MEMBER・担任・SYSTEM_ADMIN には 404、Policy にチームを渡さない")
        void 組織規程は組織ADMIN以外404() {
            given(ruleRepository.findById(1L)).willReturn(Optional.of(orgRule()));
            given(accessControlService.isAdmin(777L, 5L, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(777L)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(777L, 5L, "ORGANIZATION", "DEPUTY_ADMIN")).willReturn(false);

            assertThatThrownBy(() -> service.evaluate(200L, 1L, 777L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());
            verify(policy, never()).canRecordDaily(any(), any());
        }

        @Test
        @DisplayName("AC-22: SYSTEM_ADMIN 単独（組織の ADMIN ではない）は組織規程を 404 に畳む")
        void 組織規程はSYSTEM_ADMIN単独404() {
            given(ruleRepository.findById(1L)).willReturn(Optional.of(orgRule()));
            given(accessControlService.isAdmin(900L, 5L, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(900L)).willReturn(true);

            assertThatThrownBy(() -> service.evaluate(200L, 1L, 900L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("AC-22: 組織の DEPUTY_ADMIN は組織規程を評価できる（集計が無ければ SUMMARY_NOT_FOUND まで進む）")
        void 組織規程は組織DEPUTYが通る() {
            given(ruleRepository.findById(1L)).willReturn(Optional.of(orgRule()));
            given(accessControlService.isAdmin(800L, 5L, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(800L)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(800L, 5L, "ORGANIZATION", "DEPUTY_ADMIN")).willReturn(true);

            assertThatThrownBy(() -> service.evaluate(200L, 1L, 800L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.SUMMARY_NOT_FOUND.getMessage());
        }
    }

    // ========================================
    // getStudentEvaluations（AC-4: 返却範囲）
    // ========================================

    @Nested
    @DisplayName("getStudentEvaluations — 本人・保護者・V の教員")
    class GetStudentEvaluations {

        private AttendanceRequirementEvaluationEntity evaluation(Long ruleId) {
            return AttendanceRequirementEvaluationEntity.builder()
                    .requirementRuleId(ruleId)
                    .studentUserId(200L)
                    .summaryId(10L)
                    .status(EvaluationStatus.OK)
                    .currentAttendanceRate(new BigDecimal("90.00"))
                    .remainingAllowedAbsences(3)
                    .evaluatedAt(LocalDateTime.now())
                    .build();
        }

        private AttendanceRequirementRuleEntity rule(Long id, Long teamId) {
            AttendanceRequirementRuleEntity r = AttendanceRequirementRuleEntity.builder()
                    .teamId(teamId)
                    .academicYear((short) 2026)
                    .name("規程" + id)
                    .effectiveFrom(LocalDate.of(2026, 4, 1))
                    .build();
            ReflectionTestUtils.setField(r, "id", id);
            return r;
        }

        @Test
        @DisplayName("AC-4: 生徒本人は全クラス分が返る")
        void self_getsAll() {
            given(evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(200L))
                    .willReturn(java.util.List.of(evaluation(1L), evaluation(2L)));

            assertThat(service.getStudentEvaluations(200L, 200L)).hasSize(2);
            verify(policy, never()).canView(any(), any());
        }

        @Test
        @DisplayName("AC-4: 教員は自分が V のクラスの規程の評価だけが返る")
        void teacher_getsOnlyViewable() {
            given(evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(200L))
                    .willReturn(java.util.List.of(evaluation(1L), evaluation(2L)));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(999L, 200L);
            given(accessControlService.findActiveMembershipJoinedAtByScope(200L, "TEAM"))
                    .willReturn(java.util.Map.of(10L, LocalDateTime.now(), 20L, LocalDateTime.now()));
            given(policy.canView(999L, 10L)).willReturn(true);
            given(policy.canView(999L, 20L)).willReturn(false);
            given(ruleRepository.findAllById(any())).willReturn(java.util.List.of(rule(1L, 10L), rule(2L, 20L)));

            var result = service.getStudentEvaluations(200L, 999L);

            assertThat(result).extracting(EvaluationResponse::requirementRuleId).containsExactly(1L);
        }

        @Test
        @DisplayName("AC-4: 評価が 0 件でも V の教員は 200 の空配列（careLink 判定の 403 に落ちない）")
        void teacher_zeroEvaluations_emptyList() {
            given(evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(200L))
                    .willReturn(java.util.List.of());
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(999L, 200L);
            given(accessControlService.findActiveMembershipJoinedAtByScope(200L, "TEAM"))
                    .willReturn(java.util.Map.of(10L, LocalDateTime.now()));
            given(policy.canView(999L, 10L)).willReturn(true);

            assertThat(service.getStudentEvaluations(200L, 999L)).isEmpty();
        }

        @Test
        @DisplayName("AC-4: V のクラスが無い者（同級の一般 MEMBER・別クラスの教員）は 403 (COMMON_002)")
        void noViewableClass_forbidden() {
            given(evaluationRepository.findByStudentUserIdOrderByEvaluatedAtDesc(200L))
                    .willReturn(java.util.List.of());
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(777L, 200L);
            given(accessControlService.findActiveMembershipJoinedAtByScope(200L, "TEAM"))
                    .willReturn(java.util.Map.of(10L, LocalDateTime.now()));
            given(policy.canView(777L, 10L)).willReturn(false);

            assertThatThrownBy(() -> service.getStudentEvaluations(200L, 777L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // getAtRiskStudents（チームスコープ認可）
    // ========================================

    @Nested
    @DisplayName("getAtRiskStudents — チームスコープ認可")
    class GetAtRiskStudentsAuthorization {

        @Test
        @DisplayName("認可: checkMembership を対象チームで呼ぶ")
        void checkMembershipを呼ぶ() {
            given(evaluationRepository.findAtRiskByTeamId(any(), any())).willReturn(java.util.List.of());

            service.getAtRiskStudents(10L, null, 999L);

            verify(accessControlService).checkMembership(999L, 10L, "TEAM");
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
