package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.beta.dto.BetaPerkCriteriaResponse;
import com.mannschaft.app.billing.beta.dto.BetaPerkCriteriaUpsertRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F20.3 ベータ特典: 条件マスタ CRUD サービス（隊2・設計書 02 §4.6）。
 *
 * <p>{@code beta_perk_criteria}（{@code beta_phase} × {@code grant_kind}）の取得/upsert を担う。
 * <b>全指標 NULL の「無条件付与」は {@link BetaPerkErrorCode#CRITERIA_VALIDATION_FAILED}(400) で拒否</b>
 * （AC-N2・複数フィールド相関ゆえ DTO ではなくサービスで判定）。付与判定
 * （{@link BetaPerkEligibilityService}）はこのマスタを {@code enabled} フィルタ付きで読む。</p>
 */
@Service
@RequiredArgsConstructor
public class BetaPerkCriteriaService {

    private final BetaPerkCriteriaRepository criteriaRepository;

    /** 条件マスタ取得（未定義は 404・{@code enabled=false} も返す＝CRUD 上は存在扱い）。 */
    @Transactional(readOnly = true)
    public BetaPerkCriteriaResponse getCriteria(int betaPhase, GrantKind grantKind) {
        validatePhase(betaPhase);
        BetaPerkCriteriaEntity entity = criteriaRepository
                .findById(new BetaPerkCriteriaId(betaPhase, grantKind))
                .orElseThrow(() -> new BusinessException(BetaPerkErrorCode.CRITERIA_NOT_FOUND));
        return toResponse(entity);
    }

    /** 条件マスタ upsert（全指標 NULL は 400・AC-N2）。 */
    @Transactional
    public BetaPerkCriteriaResponse upsertCriteria(
            int betaPhase, GrantKind grantKind, BetaPerkCriteriaUpsertRequest request) {
        validatePhase(betaPhase);
        if (!request.hasAnyMetric()) {
            // 無条件付与（全指標 NULL）の防止（AC-N2）。
            throw new BusinessException(BetaPerkErrorCode.CRITERIA_VALIDATION_FAILED);
        }
        BetaPerkCriteriaEntity entity = criteriaRepository
                .findById(new BetaPerkCriteriaId(betaPhase, grantKind))
                .orElseGet(() -> BetaPerkCriteriaEntity.builder()
                        .betaPhase(betaPhase)
                        .grantKind(grantKind)
                        .build());
        entity.setEvaluationWindowDays(request.evaluationWindowDays());
        entity.setMinActiveDays(request.minActiveDays());
        entity.setMinMembershipTenureDays(request.minMembershipTenureDays());
        entity.setMinActiveMembers(request.minActiveMembers());
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
        return toResponse(criteriaRepository.save(entity));
    }

    private void validatePhase(int betaPhase) {
        if (betaPhase < 1 || betaPhase > 4) {
            throw new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID);
        }
    }

    private BetaPerkCriteriaResponse toResponse(BetaPerkCriteriaEntity e) {
        return BetaPerkCriteriaResponse.builder()
                .betaPhase(e.getBetaPhase())
                .grantKind(e.getGrantKind().name())
                .evaluationWindowDays(e.getEvaluationWindowDays())
                .minActiveDays(e.getMinActiveDays())
                .minMembershipTenureDays(e.getMinMembershipTenureDays())
                .minActiveMembers(e.getMinActiveMembers())
                .enabled(e.isEnabled())
                .build();
    }
}
