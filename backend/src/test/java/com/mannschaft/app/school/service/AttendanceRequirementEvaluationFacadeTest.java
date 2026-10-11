package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.dto.ResolveEvaluationRequest;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.service.AttendanceRequirementEvaluationService.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AttendanceRequirementEvaluationFacade}: 規程 entity 由来スコープでの認可（存在秘匿の 404）と、
 * 認可を通ってから業務 Service を呼ぶことの検証。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceRequirementEvaluationFacade 認可テスト")
class AttendanceRequirementEvaluationFacadeTest {

    @Mock
    private AttendanceRequirementEvaluationService evaluationService;

    @Mock
    private SchoolAttendanceAccessPolicy policy;

    @InjectMocks
    private AttendanceRequirementEvaluationFacade facade;

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 5L;
    private static final Long STUDENT = 200L;
    private static final Long RULE_ID = 1L;

    @Nested
    @DisplayName("evaluate — 公開入口の認可")
    class Evaluate {

        @Test
        @DisplayName("認可: 日次登録権（R）の無い者は 404（REQUIREMENT_RULE_NOT_FOUND・存在秘匿）、評価は走らない")
        void nonWriter_hidden() {
            given(evaluationService.findRuleScope(RULE_ID)).willReturn(new RuleScope(null, TEAM_ID));
            given(policy.canWriteRequirementRule(777L, null, TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> facade.evaluate(STUDENT, RULE_ID, 777L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());

            verify(evaluationService, never()).evaluateInternal(any(), any());
        }

        @Test
        @DisplayName("AC-13: R を持つが対象生徒がクラスの在籍メンバーでなければ SUMMARY_NOT_FOUND")
        void notEnrolled_summaryNotFound() {
            given(evaluationService.findRuleScope(RULE_ID)).willReturn(new RuleScope(null, TEAM_ID));
            given(policy.canWriteRequirementRule(999L, null, TEAM_ID)).willReturn(true);
            given(policy.isEnrolledStudent(TEAM_ID, STUDENT)).willReturn(false);

            assertThatThrownBy(() -> facade.evaluate(STUDENT, RULE_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.SUMMARY_NOT_FOUND.getMessage());

            verify(evaluationService, never()).evaluateInternal(any(), any());
        }

        @Test
        @DisplayName("AC-13: R を持ち対象生徒が在籍なら評価を実行する")
        void writer_enrolled_evaluates() {
            given(evaluationService.findRuleScope(RULE_ID)).willReturn(new RuleScope(null, TEAM_ID));
            given(policy.canWriteRequirementRule(999L, null, TEAM_ID)).willReturn(true);
            given(policy.isEnrolledStudent(TEAM_ID, STUDENT)).willReturn(true);

            facade.evaluate(STUDENT, RULE_ID, 999L);

            verify(evaluationService).evaluateInternal(STUDENT, RULE_ID);
        }

        @Test
        @DisplayName("AC-22: 組織スコープ規程は組織の資格が無ければ 404、在籍確認（チームの）には進まない")
        void orgRule_nonAdmin_hidden() {
            given(evaluationService.findRuleScope(RULE_ID)).willReturn(new RuleScope(ORG_ID, null));
            given(policy.canWriteRequirementRule(777L, ORG_ID, null)).willReturn(false);

            assertThatThrownBy(() -> facade.evaluate(STUDENT, RULE_ID, 777L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND.getMessage());

            verify(policy, never()).isEnrolledStudent(any(), any());
        }

        @Test
        @DisplayName("AC-22: 組織の資格がある者は組織規程を評価できる（在籍確認はチームスコープ規程のみ）")
        void orgRule_admin_evaluates() {
            given(evaluationService.findRuleScope(RULE_ID)).willReturn(new RuleScope(ORG_ID, null));
            given(policy.canWriteRequirementRule(800L, ORG_ID, null)).willReturn(true);

            facade.evaluate(STUDENT, RULE_ID, 800L);

            verify(policy, never()).isEnrolledStudent(any(), any());
            verify(evaluationService).evaluateInternal(STUDENT, RULE_ID);
        }
    }

    @Nested
    @DisplayName("resolveViolation — 認可")
    class ResolveViolation {

        private final ResolveEvaluationRequest request = new ResolveEvaluationRequest("解消理由");

        @Test
        @DisplayName("認可: 権限の無い者は 404（EVALUATION_NOT_FOUND・存在秘匿）、解消は走らない")
        void nonWriter_hidden() {
            given(evaluationService.findRuleScopeByEvaluation(50L)).willReturn(new RuleScope(null, TEAM_ID));
            given(policy.canWriteRequirementRule(777L, null, TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> facade.resolveViolation(50L, 777L, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(SchoolErrorCode.EVALUATION_NOT_FOUND.getMessage());

            verify(evaluationService, never()).resolveViolation(any(), any(), any());
        }

        @Test
        @DisplayName("権限のある者は解消できる")
        void writer_resolves() {
            given(evaluationService.findRuleScopeByEvaluation(50L)).willReturn(new RuleScope(null, TEAM_ID));
            given(policy.canWriteRequirementRule(999L, null, TEAM_ID)).willReturn(true);

            facade.resolveViolation(50L, 999L, request);

            verify(evaluationService).resolveViolation(50L, 999L, request);
        }
    }

    @Nested
    @DisplayName("閲覧")
    class View {

        @Test
        @DisplayName("AC-4: 生徒の評価一覧は Policy が解決した返却範囲を業務 Service へ渡す")
        void studentEvaluations_passesScope() {
            given(policy.resolveViewableTeamIds(STUDENT, 999L)).willReturn(Set.of(TEAM_ID));

            facade.getStudentEvaluations(STUDENT, 999L);

            verify(evaluationService).getStudentEvaluations(STUDENT, Set.of(TEAM_ID));
        }

        @Test
        @DisplayName("AC-4: V のクラスが無い者は 403、業務 Service は呼ばれない")
        void studentEvaluations_noViewable_forbidden() {
            given(policy.resolveViewableTeamIds(STUDENT, 777L))
                    .willThrow(new BusinessException(CommonErrorCode.COMMON_002));

            assertThatThrownBy(() -> facade.getStudentEvaluations(STUDENT, 777L))
                    .isInstanceOf(BusinessException.class);

            verify(evaluationService, never()).getStudentEvaluations(any(), any());
        }

        @Test
        @DisplayName("リスクあり生徒一覧は閲覧権（V）を対象チームで要求する")
        void atRisk_requiresView() {
            facade.getAtRiskStudents(TEAM_ID, null, 999L);

            verify(policy).checkCanView(999L, TEAM_ID);
            verify(evaluationService).getAtRiskStudents(TEAM_ID, null);
        }
    }
}
