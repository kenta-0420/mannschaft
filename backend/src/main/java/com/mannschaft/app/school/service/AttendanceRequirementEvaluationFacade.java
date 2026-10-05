package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.AtRiskStudentResponse;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.dto.ResolveEvaluationRequest;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.service.AttendanceRequirementEvaluationService.RuleScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 出席要件評価の認可ファサード（トランザクションの外）。
 *
 * <p>認可を業務トランザクションの外で済ませてから {@link AttendanceRequirementEvaluationService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。ruleId・evaluationId 直指定の EP は、まず readOnly で規程の
 * スコープ（entity 由来）を読み、そのスコープで認可してから、TX 内で読み直して書き込む。
 * 本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class AttendanceRequirementEvaluationFacade {

    private final AttendanceRequirementEvaluationService evaluationService;
    private final SchoolAttendanceAccessPolicy policy;

    /**
     * 生徒の評価一覧を取得する。認可（AC-4）: 本人・保護者は全クラス分、教職員は閲覧権（V）のあるクラスの規程に属する評価だけ。
     * いずれでもなければ 403（COMMON_002）。
     */
    public List<EvaluationResponse> getStudentEvaluations(Long studentUserId, Long currentUserId) {
        Set<Long> viewableTeamIds = policy.resolveViewableTeamIds(studentUserId, currentUserId);
        return evaluationService.getStudentEvaluations(studentUserId, viewableTeamIds);
    }

    /** チームのリスクあり生徒一覧を取得する。認可: 閲覧権（V）。 */
    public List<AtRiskStudentResponse> getAtRiskStudents(Long teamId, List<String> statusFilters, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return evaluationService.getAtRiskStudents(teamId, statusFilters);
    }

    /**
     * 生徒の出席要件評価を実行する（HTTP 公開入口）。
     *
     * <p>認可（AC-13/AC-22）: チームスコープ規程は日次登録権（R）を持つ担任・副担任・管理者のみ、組織スコープ規程は
     * 当該組織の ADMIN/DEPUTY_ADMIN のみ。URL パスにスコープを持たない ruleId 直指定 EP のため、権限が無い場合は
     * 403 ではなく 404（{@code REQUIREMENT_RULE_NOT_FOUND}）に収束させ、規程の存在有無を非権限者に開示しない。
     * チームスコープ規程では、対象生徒が当該クラスの在籍メンバーでなければ 404（SUMMARY_NOT_FOUND）。</p>
     */
    public EvaluationResponse evaluate(Long studentUserId, Long requirementRuleId, Long actorUserId) {
        RuleScope scope = evaluationService.findRuleScope(requirementRuleId);
        requireRuleWriterOrHide(scope, actorUserId, SchoolErrorCode.REQUIREMENT_RULE_NOT_FOUND);
        if (scope.organizationId() == null && scope.teamId() != null
                && !policy.isEnrolledStudent(scope.teamId(), studentUserId)) {
            throw new BusinessException(SchoolErrorCode.SUMMARY_NOT_FOUND);
        }
        return evaluationService.evaluateInternal(studentUserId, requirementRuleId);
    }

    /**
     * 評価違反を解消済みとして記録する。認可（AC-13/AC-22）: 評価 → 規程と辿った entity 由来スコープで
     * {@link #evaluate} と同じ判定。権限が無い場合は 404（{@code EVALUATION_NOT_FOUND}）に収束させる（存在秘匿）。
     */
    public EvaluationResponse resolveViolation(
            Long evaluationId, Long resolverUserId, ResolveEvaluationRequest request) {
        RuleScope scope = evaluationService.findRuleScopeByEvaluation(evaluationId);
        // 番人（Controller から 2 ホップ）が AccessControlService 到達を検出できるよう、
        // 補助メソッドを挟まず Policy を直接呼ぶ（evaluate の isEnrolledStudent と同じ深さ）。
        if (!policy.canWriteRequirementRule(resolverUserId, scope.organizationId(), scope.teamId())) {
            throw new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND);
        }
        return evaluationService.resolveViolation(evaluationId, resolverUserId, request);
    }

    /** 規程 entity 由来スコープの書込権限を要求する。権限が無ければ引数の ErrorCode（404 系）で存在を秘匿する。 */
    private void requireRuleWriterOrHide(RuleScope scope, Long actorUserId, SchoolErrorCode hideAs) {
        if (!policy.canWriteRequirementRule(actorUserId, scope.organizationId(), scope.teamId())) {
            throw new BusinessException(hideAs);
        }
    }
}
