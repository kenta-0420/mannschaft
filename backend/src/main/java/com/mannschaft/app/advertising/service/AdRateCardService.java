package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.AdvertisingMapper;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.AdRateCardResponse;
import com.mannschaft.app.advertising.dto.CreateAdRateCardRequest;
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import com.mannschaft.app.advertising.repository.AdRateCardRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 広告料金カードサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdRateCardService {

    private final AdRateCardRepository adRateCardRepository;
    private final AdvertisingMapper advertisingMapper;

    /**
     * 料金カード一覧を取得する（管理者用）。
     */
    public Page<AdRateCardResponse> findAll(PricingModel pricingModel, String prefecture,
                                            Boolean activeOnly, Pageable pageable) {
        // Phase 1: 全件取得後にフィルタリング（Specification 導入は Phase 2 で検討）
        Page<AdRateCardEntity> page = adRateCardRepository.findAll(pageable);
        LocalDate today = LocalDate.now();

        return page.map(entity -> {
            // フィルタ条件にマッチしない場合は null を返し、後で除外
            if (pricingModel != null && entity.getPricingModel() != pricingModel) {
                return null;
            }
            if (prefecture != null && !prefecture.equals(entity.getTargetPrefecture())) {
                return null;
            }
            if (Boolean.TRUE.equals(activeOnly)) {
                if (entity.getEffectiveFrom().isAfter(today)) {
                    return null;
                }
                if (entity.getEffectiveUntil() != null && entity.getEffectiveUntil().isBefore(today)) {
                    return null;
                }
            }
            return advertisingMapper.toRateCardResponse(entity);
        });
    }

    /**
     * 料金カードを作成する。
     */
    @Transactional
    public AdRateCardResponse create(Long createdByUserId, CreateAdRateCardRequest request) {
        AdRateCardEntity entity = AdRateCardEntity.builder()
                .targetPrefecture(request.targetPrefecture())
                .targetTemplate(request.targetTemplate())
                .pricingModel(request.pricingModel())
                .unitPrice(request.unitPrice())
                .minDailyBudget(request.minDailyBudget())
                .effectiveFrom(request.effectiveFrom())
                .createdBy(createdByUserId)
                .build();

        AdRateCardEntity saved = adRateCardRepository.save(entity);
        return advertisingMapper.toRateCardResponse(saved);
    }

    /**
     * 料金カードを削除する。
     */
    @Transactional
    public void delete(Long rateCardId) {
        AdRateCardEntity entity = adRateCardRepository.findById(rateCardId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_008));

        if (!entity.getEffectiveFrom().isAfter(LocalDate.now())) {
            throw new BusinessException(AdvertisingErrorCode.AD_009);
        }

        adRateCardRepository.deleteById(rateCardId);
    }

    /**
     * 現在有効な料金カード一覧を取得する（公開API）。
     */
    public List<PublicRateCardResponse> findCurrentRateCards(PricingModel pricingModel, String prefecture) {
        List<AdRateCardEntity> cards = adRateCardRepository.findCurrentlyEffective(LocalDate.now());

        return cards.stream()
                .filter(card -> pricingModel == null || card.getPricingModel() == pricingModel)
                .filter(card -> prefecture == null || prefecture.equals(card.getTargetPrefecture()))
                .map(advertisingMapper::toPublicRateCardResponse)
                .toList();
    }

    /**
     * 指定条件に一致する有効な料金カードを検索する。
     */
    public AdRateCardEntity matchRate(String prefecture, String template,
                                      PricingModel pricingModel, LocalDate date) {
        List<AdRateCardEntity> rates = adRateCardRepository.findMatchingRates(pricingModel, date);

        return rates.stream()
                .filter(r -> matchesCriteria(r, prefecture, template))
                .findFirst()
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_011));
    }

    /**
     * 料金カードが指定された都道府県・テンプレート条件にマッチするか判定する。
     * <p>
     * マッチング優先順位:
     * <ol>
     *   <li>都道府県 + テンプレート完全一致</li>
     *   <li>都道府県のみ一致（テンプレートは NULL）</li>
     *   <li>テンプレートのみ一致（都道府県は NULL）</li>
     *   <li>両方 NULL（デフォルト料金）</li>
     * </ol>
     */
    private boolean matchesCriteria(AdRateCardEntity card, String prefecture, String template) {
        String cardPrefecture = card.getTargetPrefecture();
        String cardTemplate = card.getTargetTemplate();

        // 完全一致
        if (cardPrefecture != null && cardTemplate != null) {
            return cardPrefecture.equals(prefecture) && cardTemplate.equals(template);
        }
        // 都道府県のみ
        if (cardPrefecture != null) {
            return cardPrefecture.equals(prefecture);
        }
        // テンプレートのみ
        if (cardTemplate != null) {
            return cardTemplate.equals(template);
        }
        // デフォルト（両方 NULL）
        return true;
    }
}
