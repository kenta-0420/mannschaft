package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.advertising.dto.AdvertiserAccountDetailResponse;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.dto.SuspendAdvertiserRequest;
import com.mannschaft.app.advertising.dto.UpdateAdvertiserAccountRequest;
import com.mannschaft.app.advertising.dto.UpdateCreditLimitRequest;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 広告主アカウントサービス。
 *
 * <p>F09.17 Phase 11-d-2 で scope ベース化（{@code ScopeType scopeType, Long scopeId}）に書き換え。
 * 互換のため旧 {@code organizationId} 引数の overload は {@code @Deprecated} で残置し、
 * 内部で {@code (ORGANIZATION, organizationId)} に詰め替えて新シグネチャに委譲する。
 * Phase 11-e で旧 overload を物理削除予定。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdvertiserAccountService {

    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AdvertisingMapper advertisingMapper;
    private final OrganizationRepository organizationRepository;
    private final StripePaymentProvider stripePaymentProvider;

    // ─────────────────────────────────────────────
    // scope ベース API (Phase 11-d-2 新規)
    // ─────────────────────────────────────────────

    /**
     * 広告主アカウントを登録する (scope ベース)。
     *
     * <p>{@code scopeType=ORGANIZATION} の場合は組織単位、{@code scopeType=TEAM} の場合は
     * チーム単位の広告主アカウントを作成する。各 scope につき同時に 1 つまで。</p>
     *
     * <p>{@code billingMethod} は F08.12 §5.0 により後払い（{@code INVOICE}）を新規に選ばせない。
     * 省略時（{@code null}）は既定値 {@code STRIPE} で作成し、明示的に {@code INVOICE} を
     * 指定した場合は拒否する（{@code AD_036}）。既存の {@code INVOICE} 行は本メソッドを通らないため
     * 影響しない。</p>
     */
    @Transactional
    public AdvertiserAccountResponse register(
            ScopeType scopeType, Long scopeId, RegisterAdvertiserRequest request) {
        if (advertiserAccountRepository
                .existsByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId)) {
            throw new BusinessException(AdvertisingErrorCode.AD_006);
        }
        if (request.billingMethod() == BillingMethod.INVOICE) {
            throw new BusinessException(AdvertisingErrorCode.AD_036);
        }

        var builder = AdvertiserAccountEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .companyName(request.companyName())
                .contactEmail(request.contactEmail());
        if (request.billingMethod() != null) {
            builder.billingMethod(request.billingMethod());
        }

        AdvertiserAccountEntity saved = advertiserAccountRepository.save(builder.build());
        return advertisingMapper.toAccountResponse(saved);
    }

    /**
     * scope で広告主アカウントを取得する。
     */
    public AdvertiserAccountResponse getByScope(ScopeType scopeType, Long scopeId) {
        AdvertiserAccountEntity entity = advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        return advertisingMapper.toAccountResponse(entity);
    }

    /**
     * 広告主アカウントのプロフィールを更新する (scope ベース)。
     */
    @Transactional
    public AdvertiserAccountResponse updateProfile(
            ScopeType scopeType, Long scopeId, UpdateAdvertiserAccountRequest request) {
        if (request.companyName() == null && request.contactEmail() == null) {
            throw new BusinessException(AdvertisingErrorCode.AD_012);
        }

        AdvertiserAccountEntity entity = advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));

        if (entity.getStatus() == AdvertiserAccountStatus.SUSPENDED) {
            throw new BusinessException(AdvertisingErrorCode.AD_010);
        }

        entity.updateProfile(
                request.companyName() != null ? request.companyName() : entity.getCompanyName(),
                request.contactEmail() != null ? request.contactEmail() : entity.getContactEmail()
        );
        return advertisingMapper.toAccountResponse(entity);
    }

    // ─────────────────────────────────────────────
    // SYSTEM_ADMIN / 共通 API（scope に依存しない）
    // ─────────────────────────────────────────────

    /**
     * 広告主アカウント一覧を取得する（SYSTEM_ADMIN用）。
     *
     * <p>scope 横断で全件返す。{@code scopeName} は
     * {@code scopeType=ORGANIZATION} の場合は組織名を解決し、それ以外は {@code null} のまま返す。
     * 表示用 scope ラベルは DTO の {@code scopeType}/{@code scopeId}/{@code scopeName} を Frontend で解釈する。</p>
     */
    public Page<AdvertiserAccountDetailResponse> findAll(AdvertiserAccountStatus status, Pageable pageable) {
        Page<AdvertiserAccountEntity> page = (status != null)
                ? advertiserAccountRepository.findByStatus(status, pageable)
                : advertiserAccountRepository.findAll(pageable);

        return page.map(entity -> {
            String scopeName = null;
            if (entity.getScopeType() == ScopeType.ORGANIZATION && entity.getScopeId() != null) {
                scopeName = organizationRepository.findById(entity.getScopeId())
                        .map(org -> org.getName())
                        .orElse(String.valueOf(entity.getScopeId()));
            }
            return new AdvertiserAccountDetailResponse(
                    entity.getId(),
                    entity.getScopeType(),
                    entity.getScopeId(),
                    scopeName,
                    entity.getStatus(),
                    entity.getCompanyName(),
                    entity.getContactEmail(),
                    entity.getBillingMethod(),
                    entity.getCreditLimit(),
                    entity.getApprovedAt(),
                    entity.getCreatedAt()
            );
        });
    }

    /**
     * 広告主アカウントを承認する。
     */
    @Transactional
    public AdvertiserAccountResponse approve(Long accountId, Long approvedByUserId) {
        AdvertiserAccountEntity entity = findById(accountId);
        try {
            entity.approve(approvedByUserId);
        } catch (IllegalStateException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_007, e);
        }
        // Stripe Customer 作成
        if (entity.getStripeCustomerId() == null) {
            try {
                String stripeCustomerId = stripePaymentProvider.createCustomer(
                        entity.getContactEmail(), entity.getId());
                entity.assignStripeCustomerId(stripeCustomerId);
                log.info("Stripe Customer 作成完了: accountId={}, stripeCustomerId={}",
                        entity.getId(), stripeCustomerId);
            } catch (Exception e) {
                log.error("Stripe Customer 作成失敗: accountId={}", entity.getId(), e);
                // Stripe Customer 作成失敗は承認処理自体を止めない
            }
        }
        return advertisingMapper.toAccountResponse(entity);
    }

    /**
     * 広告主アカウントを停止する。
     */
    @Transactional
    public AdvertiserAccountResponse suspend(Long accountId, SuspendAdvertiserRequest request) {
        AdvertiserAccountEntity entity = findById(accountId);
        try {
            entity.suspend();
        } catch (IllegalStateException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_007, e);
        }
        return advertisingMapper.toAccountResponse(entity);
    }

    /**
     * 広告主アカウントの与信限度額を更新する。
     */
    @Transactional
    public AdvertiserAccountResponse updateCreditLimit(Long accountId, UpdateCreditLimitRequest request) {
        AdvertiserAccountEntity entity = findById(accountId);
        entity.updateCreditLimit(request.creditLimit());
        return advertisingMapper.toAccountResponse(entity);
    }

    private AdvertiserAccountEntity findById(Long accountId) {
        return advertiserAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
    }

    /**
     * F09.17 Phase 11-b ε-A: メッセージ型キャンペーン {@code launch} / {@code resume} の同期 credit_limit 判定。
     *
     * <p>新規キャンペーンの {@code total_budget_yen} が広告主の {@code credit_limit} を超えていないか同期確認する。
     * 設計書 §5「credit_limit 超過時の挙動」では「credit_used + estimated_cost > credit_limit」で判定するが、
     * Phase 11-b ε-A 時点では {@code credit_used} 集計（ε-C 課金ブリッジで導入予定）がまだ無いため、
     * 単純に {@code totalBudgetYen <= creditLimit} で判定する暫定実装とする。
     * ε-C で {@code credit_used} 集計を導入したらここを差し替える。</p>
     *
     * <p>ドメイン境界: 本メソッドは F09.17 ドメインの
     * {@code AdMessagingCampaignTransitionService} から呼ばれる。
     * 広告主アカウントは同 {@code advertising} 親パッケージ内のため跨ぎ違反にはならない。</p>
     *
     * @param advertiserAccountId 広告主アカウント ID
     * @param requestedBudgetYen キャンペーンの総予算（円）
     * @return 受け入れ可能なら {@code true}
     */
    public boolean canAcceptNewCampaign(Long advertiserAccountId, Long requestedBudgetYen) {
        AdvertiserAccountEntity entity = findById(advertiserAccountId);
        if (entity.getStatus() != AdvertiserAccountStatus.ACTIVE) {
            return false;
        }
        if (requestedBudgetYen == null || requestedBudgetYen <= 0L) {
            // 予算 0 のキャンペーンは launch 不可（バリデーション側でも弾く想定）
            return false;
        }
        java.math.BigDecimal limit = entity.getCreditLimit();
        if (limit == null) {
            return false;
        }
        return limit.compareTo(java.math.BigDecimal.valueOf(requestedBudgetYen)) >= 0;
    }
}
