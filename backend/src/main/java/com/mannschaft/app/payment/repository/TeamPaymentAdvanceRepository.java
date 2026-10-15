package com.mannschaft.app.payment.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.payment.AdvanceSettlementStatus;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 協会請求の立替/精算記録リポジトリ（team_payment_advances・案3）。
 *
 * <p>テナント（organization_id）スコープのため {@link AbstractTenantAwareRepository} を継承する
 * （CLAUDE.md 原則7）。チームの立替/精算一覧・精算確認の引き当て・冪等チェックを提供する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.5 / 02_api_design.md §7</p>
 */
public interface TeamPaymentAdvanceRepository
        extends AbstractTenantAwareRepository<TeamPaymentAdvanceEntity, UUID> {

    /**
     * 立替/精算記録を ID で引く（論理削除を除外）。精算確認で使用する。
     */
    Optional<TeamPaymentAdvanceEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * チームの立替/精算一覧（idx_tpa_team で引く）。チーム ADMIN の閲覧画面の本体。
     */
    List<TeamPaymentAdvanceEntity> findByTeamIdAndDeletedAtIsNullOrderByAdvancedAtDesc(Long teamId);

    /**
     * チームの指定精算状態の立替一覧（PENDING の未精算一覧等）。
     */
    List<TeamPaymentAdvanceEntity> findByTeamIdAndSettlementStatusAndDeletedAtIsNull(
            Long teamId, AdvanceSettlementStatus settlementStatus);

    /**
     * 対象の協会請求に紐づく立替記録（1請求＝1立替の冪等チェック・重複起票防止）。
     */
    Optional<TeamPaymentAdvanceEntity> findByPaymentRequestIdAndDeletedAtIsNull(UUID paymentRequestId);
}
