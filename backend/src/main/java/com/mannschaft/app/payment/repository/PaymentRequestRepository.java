package com.mannschaft.app.payment.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 協会→加盟チーム請求リポジトリ（payment_requests）。
 *
 * <p>テナント（organization_id）スコープのため {@link AbstractTenantAwareRepository} を継承する
 * （CLAUDE.md 原則7）。協会の発行一覧・チームの受信一覧・支払い時の引き当てクエリを提供する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.2 / 02_api_design.md §7</p>
 */
public interface PaymentRequestRepository
        extends AbstractTenantAwareRepository<PaymentRequestEntity, UUID> {

    /**
     * 支払い対象の請求を ID で引く（論理削除を除外）。支払い・取消・閲覧で使用する。
     */
    Optional<PaymentRequestEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * チーム（請求先）が受信した請求一覧（idx_pr_payer で引く）。チーム視点の受信一覧 API の本体。
     */
    List<PaymentRequestEntity> findByPayerScopeKindAndPayerScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            ScopeKind payerScopeKind, Long payerScopeId);

    /**
     * 協会（請求元）が発行した請求一覧（idx_pr_issuer で引く）。協会視点の発行一覧 API の本体。
     */
    List<PaymentRequestEntity> findByIssuerScopeKindAndIssuerScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            ScopeKind issuerScopeKind, Long issuerScopeId);

    /**
     * 協会の発行請求のうち指定状態の件数（回収率＝PAID 件数の集計用）。
     */
    long countByIssuerScopeKindAndIssuerScopeIdAndStatusAndDeletedAtIsNull(
            ScopeKind issuerScopeKind, Long issuerScopeId, PaymentRequestStatus status);
}
