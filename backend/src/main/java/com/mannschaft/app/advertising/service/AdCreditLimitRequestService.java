package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.CreditLimitRequestStatus;
import com.mannschaft.app.advertising.dto.CreateCreditLimitRequest;
import com.mannschaft.app.advertising.dto.CreditLimitRequestDetailResponse;
import com.mannschaft.app.advertising.dto.CreditLimitRequestResponse;
import com.mannschaft.app.advertising.dto.RejectCreditLimitRequest;
import com.mannschaft.app.advertising.entity.AdCreditLimitRequestEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCreditLimitRequestRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 広告主クレジット増額申請サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdCreditLimitRequestService {

    private final AdCreditLimitRequestRepository adCreditLimitRequestRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * 増額申請を作成する。
     */
    @Transactional
    public CreditLimitRequestResponse create(Long organizationId, CreateCreditLimitRequest request) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));

        if (request.requestedLimit().compareTo(account.getCreditLimit()) <= 0) {
            throw new BusinessException(AdvertisingErrorCode.AD_020);
        }

        if (adCreditLimitRequestRepository.existsByAdvertiserAccountIdAndStatus(
                account.getId(), CreditLimitRequestStatus.PENDING)) {
            throw new BusinessException(AdvertisingErrorCode.AD_018);
        }

        AdCreditLimitRequestEntity entity = AdCreditLimitRequestEntity.builder()
                .advertiserAccountId(account.getId())
                .currentLimit(account.getCreditLimit())
                .requestedLimit(request.requestedLimit())
                .reason(request.reason())
                .build();

        AdCreditLimitRequestEntity saved = adCreditLimitRequestRepository.save(entity);
        return advertisingMapper.toCreditLimitRequestResponse(saved);
    }

    /**
     * 自組織の増額申請履歴を取得する。
     */
    public List<CreditLimitRequestResponse> findByOrganizationId(Long organizationId) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        return adCreditLimitRequestRepository.findByAdvertiserAccountId(account.getId()).stream()
                .map(advertisingMapper::toCreditLimitRequestResponse)
                .toList();
    }

    /**
     * 全増額申請一覧を取得する（SYSTEM_ADMIN用）。
     */
    public Page<CreditLimitRequestDetailResponse> findAll(CreditLimitRequestStatus status, Pageable pageable) {
        Page<AdCreditLimitRequestEntity> page = (status != null)
                ? adCreditLimitRequestRepository.findByStatus(status, pageable)
                : adCreditLimitRequestRepository.findAll(pageable);
        return page.map(entity -> {
            // TODO: Phase 2+ で advertiser_accounts → organizations JOIN して companyName を取得
            String companyName = String.valueOf(entity.getAdvertiserAccountId());
            return new CreditLimitRequestDetailResponse(
                    entity.getId(),
                    entity.getAdvertiserAccountId(),
                    companyName,
                    entity.getCurrentLimit(),
                    entity.getRequestedLimit(),
                    entity.getReason(),
                    entity.getStatus(),
                    entity.getReviewedAt(),
                    entity.getReviewNote(),
                    entity.getCreatedAt()
            );
        });
    }

    /**
     * 増額申請を承認する。
     */
    @Transactional
    public CreditLimitRequestResponse approve(Long requestId, Long reviewedByUserId) {
        AdCreditLimitRequestEntity request = findRequestById(requestId);
        try {
            request.approve(reviewedByUserId);
        } catch (IllegalStateException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_019, e);
        }

        // advertiser_accounts.credit_limit を更新
        AdvertiserAccountEntity account = advertiserAccountRepository.findById(request.getAdvertiserAccountId())
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        account.updateCreditLimit(request.getRequestedLimit());

        return advertisingMapper.toCreditLimitRequestResponse(request);
    }

    /**
     * 増額申請を却下する。
     */
    @Transactional
    public CreditLimitRequestResponse reject(Long requestId, Long reviewedByUserId, RejectCreditLimitRequest rejectRequest) {
        AdCreditLimitRequestEntity request = findRequestById(requestId);
        try {
            request.reject(reviewedByUserId, rejectRequest.reviewNote());
        } catch (IllegalStateException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_019, e);
        }
        return advertisingMapper.toCreditLimitRequestResponse(request);
    }

    private AdCreditLimitRequestEntity findRequestById(Long requestId) {
        return adCreditLimitRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_017));
    }
}
