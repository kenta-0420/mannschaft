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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 広告主アカウントサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdvertiserAccountService {

    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * 広告主アカウントを登録する。
     */
    @Transactional
    public AdvertiserAccountResponse register(Long organizationId, RegisterAdvertiserRequest request) {
        if (advertiserAccountRepository.existsByOrganizationId(organizationId)) {
            throw new BusinessException(AdvertisingErrorCode.AD_006);
        }

        AdvertiserAccountEntity entity = AdvertiserAccountEntity.builder()
                .organizationId(organizationId)
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

        return page.map(entity -> new AdvertiserAccountDetailResponse(
                entity.getId(),
                entity.getOrganizationId(),
                // TODO: Phase 2 で organizations テーブルから組織名を取得する
                String.valueOf(entity.getOrganizationId()),
                entity.getStatus(),
                entity.getCompanyName(),
                entity.getContactEmail(),
                entity.getBillingMethod(),
                entity.getCreditLimit(),
                entity.getApprovedAt(),
                entity.getCreatedAt()
        ));
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
        // TODO: Stripe Customer 作成処理を追加する
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
}
