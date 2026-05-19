package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.dto.AdvertiserAccountDetailResponse;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.dto.RegisterAdvertiserRequest;
import com.mannschaft.app.advertising.dto.SuspendAdvertiserRequest;
import com.mannschaft.app.advertising.dto.UpdateAdvertiserAccountRequest;
import com.mannschaft.app.advertising.dto.UpdateCreditLimitRequest;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
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

    /**
     * 広告主アカウントを登録する。
     */
    @Transactional
    public AdvertiserAccountResponse register(Long organizationId, RegisterAdvertiserRequest request) {
        if (advertiserAccountRepository.existsByOrganizationId(organizationId)) {
            throw new BusinessException(AdvertisingErrorCode.AD_006);
        }

        // F09.17 Phase 11-d-1: scope ベース化。既存 organizationId 引数を ORGANIZATION スコープとして埋める。
        // Phase 11-d-2 で scopeType/scopeId を引数として受け取る形に書き換える。
        AdvertiserAccountEntity entity = AdvertiserAccountEntity.builder()
                .organizationId(organizationId)
                .scopeType(com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION)
                .scopeId(organizationId)
                .companyName(request.companyName())
                .contactEmail(request.contactEmail())
                .billingMethod(request.billingMethod())
                .build();

        AdvertiserAccountEntity saved = advertiserAccountRepository.save(entity);
        return advertisingMapper.toAccountResponse(saved);
    }

    /**
     * 組織IDで広告主アカウントを取得する。
     */
    public AdvertiserAccountResponse getByOrganizationId(Long organizationId) {
        AdvertiserAccountEntity entity = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        return advertisingMapper.toAccountResponse(entity);
    }

    /**
     * 広告主アカウントのプロフィールを更新する。
     */
    @Transactional
    public AdvertiserAccountResponse updateProfile(Long organizationId, UpdateAdvertiserAccountRequest request) {
        if (request.companyName() == null && request.contactEmail() == null) {
            throw new BusinessException(AdvertisingErrorCode.AD_012);
        }

        AdvertiserAccountEntity entity = advertiserAccountRepository.findByOrganizationId(organizationId)
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

    /**
     * 広告主アカウント一覧を取得する（SYSTEM_ADMIN用）。
     */
    public Page<AdvertiserAccountDetailResponse> findAll(AdvertiserAccountStatus status, Pageable pageable) {
        Page<AdvertiserAccountEntity> page = (status != null)
                ? advertiserAccountRepository.findByStatus(status, pageable)
                : advertiserAccountRepository.findAll(pageable);

        return page.map(entity -> {
            String organizationName = organizationRepository.findById(entity.getOrganizationId())
                    .map(org -> org.getName())
                    .orElse(String.valueOf(entity.getOrganizationId()));
            return new AdvertiserAccountDetailResponse(
                    entity.getId(),
                    entity.getOrganizationId(),
                    organizationName,
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
