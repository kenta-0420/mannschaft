package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.AffiliateProvider;
import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.dto.UpdateAffiliateConfigRequest;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アフィリエイト広告設定サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AffiliateConfigService {

    private final AffiliateConfigRepository affiliateConfigRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * アフィリエイト設定一覧を取得する（SYSTEM_ADMIN用）。
     */
    public Page<AffiliateConfigResponse> findAll(Pageable pageable) {
        return affiliateConfigRepository.findAllByOrderByDisplayPriorityAsc(pageable)
                .map(advertisingMapper::toResponse);
    }

    /**
     * アフィリエイト設定を作成する。
     */
    @Transactional
    public AffiliateConfigResponse create(CreateAffiliateConfigRequest request) {
        validateProvider(request.getProvider());
        validatePlacement(request.getPlacement());
        validateDateRange(request.getActiveFrom(), request.getActiveUntil());

        AffiliateConfigEntity entity = advertisingMapper.toEntity(request);
        AffiliateConfigEntity saved = affiliateConfigRepository.save(entity);
        return advertisingMapper.toResponse(saved);
    }

    /**
     * アフィリエイト設定を更新する。
     */
    @Transactional
    public AffiliateConfigResponse update(Long id, UpdateAffiliateConfigRequest request) {
        validateProvider(request.getProvider());
        validatePlacement(request.getPlacement());
        validateDateRange(request.getActiveFrom(), request.getActiveUntil());

        AffiliateConfigEntity entity = findById(id);
        entity.update(
                AffiliateProvider.valueOf(request.getProvider()),
                request.getTagId(),
                AdPlacement.valueOf(request.getPlacement()),
                request.getDescription(),
                request.getBannerImageUrl(),
                request.getBannerWidth(),
                request.getBannerHeight(),
                request.getAltText(),
                request.getActiveFrom(),
                request.getActiveUntil(),
                request.getDisplayPriority()
        );
        return advertisingMapper.toResponse(entity);
    }

    /**
     * 有効/無効を切り替える。
     */
    @Transactional
    public AffiliateConfigResponse toggle(Long id) {
        AffiliateConfigEntity entity = findById(id);
        entity.toggleActive();
        return advertisingMapper.toResponse(entity);
    }

    /**
     * アフィリエイト設定を論理削除する。
     */
    @Transactional
    public void delete(Long id) {
        AffiliateConfigEntity entity = findById(id);
        entity.softDelete();
    }

    /**
     * 現在有効な広告一覧を取得する（公開API）。
     */
    public List<ActiveAdResponse> findActiveAds() {
        return affiliateConfigRepository.findActiveAds(LocalDateTime.now()).stream()
                .map(advertisingMapper::toActiveAdResponse)
                .toList();
    }

    private AffiliateConfigEntity findById(Long id) {
        return affiliateConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_001));
    }

    private void validateProvider(String provider) {
        try {
            AffiliateProvider.valueOf(provider);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_002);
        }
    }

    private void validatePlacement(String placement) {
        try {
            AdPlacement.valueOf(placement);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
    }

    private void validateDateRange(LocalDateTime from, LocalDateTime until) {
        if (from != null && until != null && from.isAfter(until)) {
            throw new BusinessException(AdvertisingErrorCode.AD_004);
        }
    }
}
