package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.DisclosedEvaluationResponse;
import com.mannschaft.app.school.dto.DisclosureRequest;
import com.mannschaft.app.school.dto.DisclosureResponse;
import com.mannschaft.app.school.dto.WithholdRequest;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureDecision;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceDisclosureRecordRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 出席要件評価開示判断サービス（F03.13 Phase 15）。
 *
 * <p>教員が評価結果を生徒・保護者へ開示するかどうかを判断し記録する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class DisclosureService {

    private final AttendanceDisclosureRecordRepository disclosureRepository;
    private final AttendanceRequirementEvaluationRepository evaluationRepository;
    private final AttendanceRequirementRuleRepository ruleRepository;

    /**
     * 評価結果を生徒・保護者へ開示する。
     *
     * @param teamId        チームID（認可チェック用）
     * @param evaluationId  評価ID
     * @param req           開示リクエスト
     * @param teacherUserId 担任のユーザーID
     * @return 開示判断レスポンス
     */
    @Transactional
    public DisclosureResponse disclose(Long teamId, Long evaluationId,
                                       DisclosureRequest req, Long teacherUserId) {
        AttendanceRequirementEvaluationEntity evaluation = findAndVerifyEvaluation(teamId, evaluationId);

        AttendanceDisclosureRecordEntity record = AttendanceDisclosureRecordEntity.builder()
                .evaluationId(evaluation.getId())
                .studentUserId(evaluation.getStudentUserId())
                .decision(DisclosureDecision.DISCLOSED)
                .mode(req.mode())
                .recipients(req.recipients())
                .message(req.message())
                .decidedBy(teacherUserId)
                .build();

        AttendanceDisclosureRecordEntity saved = disclosureRepository.save(record);
        log.info("出席要件開示: evaluationId={}, mode={}, recipients={}",
                evaluationId, req.mode(), req.recipients());

        return DisclosureResponse.from(saved);
    }

    /**
     * 評価結果を今は開示しない判断を記録する。
     *
     * @param teamId        チームID（認可チェック用）
     * @param evaluationId  評価ID
     * @param req           非開示リクエスト
     * @param teacherUserId 担任のユーザーID
     * @return 非開示判断レスポンス
     */
    @Transactional
    public DisclosureResponse withhold(Long teamId, Long evaluationId,
                                       WithholdRequest req, Long teacherUserId) {
        AttendanceRequirementEvaluationEntity evaluation = findAndVerifyEvaluation(teamId, evaluationId);

        AttendanceDisclosureRecordEntity record = AttendanceDisclosureRecordEntity.builder()
                .evaluationId(evaluation.getId())
                .studentUserId(evaluation.getStudentUserId())
                .decision(DisclosureDecision.WITHHELD)
                .withholdReason(req.withholdReason())
                .decidedBy(teacherUserId)
                .build();

        AttendanceDisclosureRecordEntity saved = disclosureRepository.save(record);
        log.info("出席要件非開示: evaluationId={}", evaluationId);

        return DisclosureResponse.from(saved);
    }

    /**
     * 開示・非開示判断の履歴を取得する（教員のみ）。
     *
     * @param teamId       チームID（認可チェック用）
     * @param evaluationId 評価ID
     * @return 開示判断履歴のリスト（判断日降順）
     */
    public List<DisclosureResponse> getDisclosureHistory(Long teamId, Long evaluationId) {
        findAndVerifyEvaluation(teamId, evaluationId);

        return disclosureRepository.findByEvaluationIdOrderByDecidedAtDesc(evaluationId)
                .stream()
                .map(DisclosureResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 自分に開示された評価一覧を取得する（生徒・保護者向け）。
     * 最新の開示判断が DISCLOSED のもののみ返す。
     *
     * @param userId 対象ユーザーID（生徒または保護者）
     * @return 開示済み評価情報のリスト
     */
    public List<DisclosedEvaluationResponse> getDisclosedEvaluationsForUser(Long userId) {
        List<AttendanceDisclosureRecordEntity> allRecords =
                disclosureRepository.findByStudentUserIdOrderByDecidedAtDesc(userId);

        // evaluationId ごとにグループ化し、最新レコードが DISCLOSED のものだけ抽出
        Map<Long, AttendanceDisclosureRecordEntity> latestByEvaluation = allRecords.stream()
                .collect(Collectors.toMap(
                        AttendanceDisclosureRecordEntity::getEvaluationId,
                        r -> r,
                        (existing, replacement) -> existing  // 降順なので最初が最新
                ));

        return latestByEvaluation.values().stream()
                .filter(r -> r.getDecision() == DisclosureDecision.DISCLOSED)
                .map(record -> buildDisclosedEvaluationResponse(record))
                .collect(Collectors.toList());
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * 評価を取得し、チームIDとの整合性を検証する。
     *
     * @param teamId       チームID
     * @param evaluationId 評価ID
     * @return 検証済みの評価エンティティ
     */
    private AttendanceRequirementEvaluationEntity findAndVerifyEvaluation(
            Long teamId, Long evaluationId) {
        AttendanceRequirementEvaluationEntity evaluation = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        // 評価の規程がこのチームに属するか確認
        AttendanceRequirementRuleEntity rule = ruleRepository
                .findById(evaluation.getRequirementRuleId())
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        if (!teamId.equals(rule.getTeamId())) {
            throw new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND);
        }

        return evaluation;
    }

    /**
     * 開示判断記録から DisclosedEvaluationResponse を構築する。
     *
     * @param record 開示判断記録
     * @return 開示済み評価情報
     */
    private DisclosedEvaluationResponse buildDisclosedEvaluationResponse(
            AttendanceDisclosureRecordEntity record) {
        AttendanceRequirementEvaluationEntity evaluation = evaluationRepository
                .findById(record.getEvaluationId())
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        AttendanceRequirementRuleEntity rule = ruleRepository
                .findById(evaluation.getRequirementRuleId())
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.EVALUATION_NOT_FOUND));

        boolean withNumbers = record.getMode() ==
                AttendanceDisclosureRecordEntity.DisclosureMode.WITH_NUMBERS;

        return new DisclosedEvaluationResponse(
                evaluation.getId(),
                rule.getId(),
                rule.getName(),
                evaluation.getStatus().name(),
                record.getMode() != null ? record.getMode().name() : null,
                record.getMessage(),
                record.getDecidedAt(),
                withNumbers ? evaluation.getCurrentAttendanceRate() : null,
                withNumbers ? evaluation.getRemainingAllowedAbsences() : null
        );
    }
}
