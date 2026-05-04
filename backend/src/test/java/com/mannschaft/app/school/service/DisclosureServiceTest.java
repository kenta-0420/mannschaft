package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.DisclosedEvaluationResponse;
import com.mannschaft.app.school.dto.DisclosureRequest;
import com.mannschaft.app.school.dto.DisclosureResponse;
import com.mannschaft.app.school.dto.WithholdRequest;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureDecision;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureMode;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureRecipients;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceDisclosureRecordRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F03.13 Phase 15: {@link DisclosureService} 単体テスト。
 *
 * <p>開示・非開示判断の記録・取得フローを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureService 単体テスト")
class DisclosureServiceTest {

    @InjectMocks
    private DisclosureService service;

    @Mock
    private AttendanceDisclosureRecordRepository disclosureRepository;

    @Mock
    private AttendanceRequirementEvaluationRepository evaluationRepository;

    @Mock
    private AttendanceRequirementRuleRepository ruleRepository;

    // ========================================
    // disclose
    // ========================================

    @Nested
    @DisplayName("disclose — 開示記録")
    class Disclose {

        @Test
        @DisplayName("正常系: 開示記録が保存されメッセージが返る（mode=WITH_NUMBERS, recipients=BOTH）")
        void disclose_正常_開示記録が保存されメッセージが返る() {
            // 評価・規程のスタブ
            AttendanceRequirementEvaluationEntity evaluation = buildEvaluation(1L, 10L, 100L);
            AttendanceRequirementRuleEntity rule = buildRule(10L, 5L);
            given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
            given(ruleRepository.findById(10L)).willReturn(Optional.of(rule));

            // 保存後の記録
            AttendanceDisclosureRecordEntity saved = AttendanceDisclosureRecordEntity.builder()
                    .evaluationId(1L)
                    .studentUserId(100L)
                    .decision(DisclosureDecision.DISCLOSED)
                    .mode(DisclosureMode.WITH_NUMBERS)
                    .recipients(DisclosureRecipients.BOTH)
                    .message("出席状況が良好です。")
                    .decidedBy(200L)
                    .build();
            ReflectionTestUtils.setField(saved, "id", 50L);
            given(disclosureRepository.save(any())).willReturn(saved);

            DisclosureRequest req = new DisclosureRequest(
                    DisclosureMode.WITH_NUMBERS,
                    DisclosureRecipients.BOTH,
                    "出席状況が良好です。"
            );

            DisclosureResponse result = service.disclose(5L, 1L, req, 200L);

            assertThat(result.id()).isEqualTo(50L);
            assertThat(result.evaluationId()).isEqualTo(1L);
            assertThat(result.decision()).isEqualTo("DISCLOSED");
            assertThat(result.mode()).isEqualTo("WITH_NUMBERS");
            assertThat(result.recipients()).isEqualTo("BOTH");
            assertThat(result.message()).isEqualTo("出席状況が良好です。");
            assertThat(result.decidedBy()).isEqualTo(200L);
            verify(disclosureRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 評価が存在しない場合 BusinessException をスロー")
        void disclose_評価が存在しない場合_BusinessExceptionをスロー() {
            given(evaluationRepository.findById(999L)).willReturn(Optional.empty());

            DisclosureRequest req = new DisclosureRequest(
                    DisclosureMode.WITH_NUMBERS, DisclosureRecipients.BOTH, null);

            assertThatThrownBy(() -> service.disclose(5L, 999L, req, 200L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("異常系: teamId が一致しない場合 BusinessException をスロー")
        void disclose_teamIdが一致しない場合_BusinessExceptionをスロー() {
            // 評価は teamId=10 の規程に属しているが、要求 teamId=99
            AttendanceRequirementEvaluationEntity evaluation = buildEvaluation(1L, 10L, 100L);
            AttendanceRequirementRuleEntity rule = buildRule(10L, 5L); // teamId=10 の規程
            given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
            given(ruleRepository.findById(10L)).willReturn(Optional.of(rule));

            DisclosureRequest req = new DisclosureRequest(
                    DisclosureMode.WITH_NUMBERS, DisclosureRecipients.BOTH, null);

            // teamId=99 で呼び出す（rule の teamId=10 と不一致）
            assertThatThrownBy(() -> service.disclose(99L, 1L, req, 200L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_NOT_FOUND.getMessage());
        }
    }

    // ========================================
    // withhold
    // ========================================

    @Nested
    @DisplayName("withhold — 非開示記録")
    class Withhold {

        @Test
        @DisplayName("正常系: 非開示記録が保存される")
        void withhold_正常_非開示記録が保存される() {
            AttendanceRequirementEvaluationEntity evaluation = buildEvaluation(1L, 10L, 100L);
            AttendanceRequirementRuleEntity rule = buildRule(10L, 5L);
            given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
            given(ruleRepository.findById(10L)).willReturn(Optional.of(rule));

            AttendanceDisclosureRecordEntity saved = AttendanceDisclosureRecordEntity.builder()
                    .evaluationId(1L)
                    .studentUserId(100L)
                    .decision(DisclosureDecision.WITHHELD)
                    .withholdReason("現在面談調整中のため開示を保留する。")
                    .decidedBy(200L)
                    .build();
            ReflectionTestUtils.setField(saved, "id", 51L);
            given(disclosureRepository.save(any())).willReturn(saved);

            WithholdRequest req = new WithholdRequest("現在面談調整中のため開示を保留する。");

            DisclosureResponse result = service.withhold(5L, 1L, req, 200L);

            assertThat(result.id()).isEqualTo(51L);
            assertThat(result.decision()).isEqualTo("WITHHELD");
            assertThat(result.mode()).isNull();
            assertThat(result.recipients()).isNull();
            verify(disclosureRepository).save(any());
        }
    }

    // ========================================
    // getDisclosureHistory
    // ========================================

    @Nested
    @DisplayName("getDisclosureHistory — 判断履歴取得")
    class GetDisclosureHistory {

        @Test
        @DisplayName("正常系: 時系列降順で返る")
        void getDisclosureHistory_正常_時系列降順で返る() {
            AttendanceRequirementEvaluationEntity evaluation = buildEvaluation(1L, 10L, 100L);
            AttendanceRequirementRuleEntity rule = buildRule(10L, 5L);
            given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
            given(ruleRepository.findById(10L)).willReturn(Optional.of(rule));

            // 2件の履歴（降順）
            AttendanceDisclosureRecordEntity newer = buildRecord(2L, 1L, 100L,
                    DisclosureDecision.DISCLOSED, LocalDateTime.now().minusHours(1));
            AttendanceDisclosureRecordEntity older = buildRecord(1L, 1L, 100L,
                    DisclosureDecision.WITHHELD, LocalDateTime.now().minusDays(1));
            given(disclosureRepository.findByEvaluationIdOrderByDecidedAtDesc(1L))
                    .willReturn(List.of(newer, older));

            List<DisclosureResponse> result = service.getDisclosureHistory(5L, 1L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo(2L);
            assertThat(result.get(0).decision()).isEqualTo("DISCLOSED");
            assertThat(result.get(1).id()).isEqualTo(1L);
            assertThat(result.get(1).decision()).isEqualTo("WITHHELD");
        }
    }

    // ========================================
    // getDisclosedEvaluationsForUser
    // ========================================

    @Nested
    @DisplayName("getDisclosedEvaluationsForUser — 生徒向け開示評価取得")
    class GetDisclosedEvaluationsForUser {

        @Test
        @DisplayName("正常系: 最新がWITHHELD → リストに含まれない")
        void getDisclosedEvaluationsForUser_最新がWITHHELD_リストに含まれない() {
            // 同じ evaluationId=1 に対して先に DISCLOSED、後に WITHHELD（最新は WITHHELD）
            AttendanceDisclosureRecordEntity latestWithheld = buildRecord(2L, 1L, 100L,
                    DisclosureDecision.WITHHELD, LocalDateTime.now().minusHours(1));
            AttendanceDisclosureRecordEntity olderDisclosed = buildRecord(1L, 1L, 100L,
                    DisclosureDecision.DISCLOSED, LocalDateTime.now().minusDays(1));

            // findByStudentUserIdOrderByDecidedAtDesc は降順（最新が先頭）
            given(disclosureRepository.findByStudentUserIdOrderByDecidedAtDesc(100L))
                    .willReturn(List.of(latestWithheld, olderDisclosed));

            List<DisclosedEvaluationResponse> result = service.getDisclosedEvaluationsForUser(100L);

            // 最新が WITHHELD なので含まれない
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 最新がDISCLOSED → リストに含まれる")
        void getDisclosedEvaluationsForUser_最新がDISCLOSED_リストに含まれる() {
            // evaluationId=1 の最新が DISCLOSED
            AttendanceDisclosureRecordEntity latestDisclosed = buildRecord(2L, 1L, 100L,
                    DisclosureDecision.DISCLOSED, LocalDateTime.now().minusHours(1));
            ReflectionTestUtils.setField(latestDisclosed, "mode", DisclosureMode.WITH_NUMBERS);

            given(disclosureRepository.findByStudentUserIdOrderByDecidedAtDesc(100L))
                    .willReturn(List.of(latestDisclosed));

            // 評価・規程のスタブ
            AttendanceRequirementEvaluationEntity evaluation = buildEvaluation(1L, 10L, 100L);
            AttendanceRequirementRuleEntity rule = buildRule(10L, 5L);
            given(evaluationRepository.findById(1L)).willReturn(Optional.of(evaluation));
            given(ruleRepository.findById(10L)).willReturn(Optional.of(rule));

            List<DisclosedEvaluationResponse> result = service.getDisclosedEvaluationsForUser(100L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).evaluationId()).isEqualTo(1L);
            assertThat(result.get(0).mode()).isEqualTo("WITH_NUMBERS");
            // WITH_NUMBERS なので数値も含まれる
            assertThat(result.get(0).currentRate()).isNotNull();
        }
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /** テスト用の評価エンティティを構築する。 */
    private AttendanceRequirementEvaluationEntity buildEvaluation(
            Long evaluationId, Long requirementRuleId, Long studentUserId) {
        AttendanceRequirementEvaluationEntity entity = AttendanceRequirementEvaluationEntity.builder()
                .requirementRuleId(requirementRuleId)
                .studentUserId(studentUserId)
                .summaryId(1L)
                .status(EvaluationStatus.RISK)
                .currentAttendanceRate(new BigDecimal("78.00"))
                .remainingAllowedAbsences(3)
                .evaluatedAt(LocalDateTime.now().minusDays(1))
                .build();
        ReflectionTestUtils.setField(entity, "id", evaluationId);
        return entity;
    }

    /** テスト用の規程エンティティを構築する（teamId を指定）。 */
    private AttendanceRequirementRuleEntity buildRule(Long ruleId, Long teamId) {
        AttendanceRequirementRuleEntity rule = AttendanceRequirementRuleEntity.builder()
                .teamId(teamId)
                .academicYear((short) 2026)
                .name("テスト出席要件規程")
                .minAttendanceRate(new BigDecimal("80"))
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .build();
        ReflectionTestUtils.setField(rule, "id", ruleId);
        return rule;
    }

    /** テスト用の開示判断記録エンティティを構築する。 */
    private AttendanceDisclosureRecordEntity buildRecord(
            Long id, Long evaluationId, Long studentUserId,
            DisclosureDecision decision, LocalDateTime decidedAt) {
        AttendanceDisclosureRecordEntity record = AttendanceDisclosureRecordEntity.builder()
                .evaluationId(evaluationId)
                .studentUserId(studentUserId)
                .decision(decision)
                .decidedBy(200L)
                .decidedAt(decidedAt)
                .build();
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
