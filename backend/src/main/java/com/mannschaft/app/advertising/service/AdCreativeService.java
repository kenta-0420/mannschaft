package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 広告クリエイティブ管理サービス。
 * 広告主によるクリエイティブ CRUD と SYSTEM_ADMIN による審査を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdCreativeService {

    private final AdEntityRepository adEntityRepository;

    /**
     * 広告クリエイティブを新規作成する。
     *
     * @param campaignId 対象キャンペーン ID
     * @param request    作成リクエスト
     * @return 作成されたクリエイティブのレスポンス
     */
    @Transactional
    public AdCreativeResponse create(Long campaignId, CreateAdCreativeRequest request) {
        AdEntity entity = AdEntity.builder()
                .campaignId(campaignId)
                .title(request.title())
                .imageUrl(request.imageUrl())
                .destinationUrl(request.destinationUrl())
                .placement(request.placement())
                .width(request.width())
                .height(request.height())
                .altText(request.altText())
                .build();
        AdEntity saved = adEntityRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * 広告クリエイティブを更新する。null フィールドは変更しない。
     *
     * @param adId       クリエイティブ ID
     * @param campaignId 対象キャンペーン ID（所有権検証用）
     * @param request    更新リクエスト
     * @return 更新後のレスポンス
     */
    @Transactional
    public AdCreativeResponse update(Long adId, Long campaignId, UpdateAdCreativeRequest request) {
        AdEntity entity = findByCampaignAndId(adId, campaignId);
        if (entity.getStatus() == AdEntity.AdStatus.ENDED) {
            throw new BusinessException(AdvertisingErrorCode.AD_025);
        }
        entity.updateCreative(request.title(), request.imageUrl(), request.destinationUrl(),
                request.placement(), request.width(), request.height(), request.altText());
        return toResponse(entity);
    }

    /**
     * 広告クリエイティブを論理削除する（status = ENDED）。
     *
     * @param adId       クリエイティブ ID
     * @param campaignId 対象キャンペーン ID（所有権検証用）
     */
    @Transactional
    public void delete(Long adId, Long campaignId) {
        AdEntity entity = findByCampaignAndId(adId, campaignId);
        entity.softDelete();
    }

    /**
     * キャンペーンに紐づく全クリエイティブ一覧を取得する。
     *
     * @param campaignId 対象キャンペーン ID
     * @return クリエイティブ一覧
     */
    public List<AdCreativeResponse> findByCampaignId(Long campaignId) {
        return adEntityRepository.findByCampaignId(campaignId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * クリエイティブ ID で取得する。
     *
     * @param adId クリエイティブ ID
     * @return クリエイティブレスポンス
     */
    public AdCreativeResponse findById(Long adId) {
        AdEntity entity = adEntityRepository.findById(adId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_024));
        return toResponse(entity);
    }

    /**
     * 全クリエイティブ一覧を取得する（SYSTEM_ADMIN用）。status でフィルタリング可能。
     *
     * @param status フィルタする status（null の場合は全件取得）
     * @return クリエイティブ一覧
     */
    public List<AdCreativeResponse> findAll(AdEntity.AdStatus status) {
        List<AdEntity> entities;
        if (status != null) {
            entities = adEntityRepository.findAllByStatus(status);
        } else {
            entities = adEntityRepository.findAll();
        }
        return entities.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * クリエイティブを審査承認する（DRAFT → ACTIVE）。
     *
     * @param adId クリエイティブ ID
     * @return 承認後のレスポンス
     */
    @Transactional
    public AdCreativeResponse approve(Long adId) {
        AdEntity entity = adEntityRepository.findById(adId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_024));
        entity.approve();
        return toResponse(entity);
    }

    /**
     * クリエイティブを審査却下する（DRAFT → ENDED）。
     *
     * @param adId クリエイティブ ID
     * @return 却下後のレスポンス
     */
    @Transactional
    public AdCreativeResponse reject(Long adId) {
        AdEntity entity = adEntityRepository.findById(adId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_024));
        entity.reject();
        return toResponse(entity);
    }

    // ─── private helper ───

    private AdEntity findByCampaignAndId(Long adId, Long campaignId) {
        return adEntityRepository.findByIdAndCampaignId(adId, campaignId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_026));
    }

    private AdCreativeResponse toResponse(AdEntity entity) {
        return new AdCreativeResponse(
                entity.getId(),
                entity.getCampaignId(),
                entity.getTitle(),
                entity.getImageUrl(),
                entity.getDestinationUrl(),
                entity.getStatus().name(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                // F09.19.1 拡張（骨格）: 保存ロジックは出陣で実装するため現状は Entity 値の素通し
                entity.getPlacement(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getAltText()
        );
    }
}
