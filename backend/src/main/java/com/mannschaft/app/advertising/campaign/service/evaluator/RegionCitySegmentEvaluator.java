package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 REGION_CITY セグメント評価器（Phase A スタブ）。
 *
 * <p>DB 基盤（users.city_code_hash）は Phase A で整備済み。
 * ただし、ユーザーが市区町村を登録する UI / API（プロフィール更新経路）は
 * Phase B で実装予定のため、現時点で city_code_hash にデータが存在しない。
 * Phase B 実装時に {@link SegmentDataSourceNotAvailableException} 箇所を
 * users.city_code_hash IN (:hashes) クエリに差し替える。</p>
 *
 * <p>segment_value 形式: {@code {"cities": ["131041", "141003"]}}</p>
 * <p>市区町村コードは JIS X 0402 に準拠（例: 新宿区=131041）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionCitySegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_CITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object cityObj = value.get("cities");
        if (!(cityObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_CITY segment に cities 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> cities = rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isBlank())
                .toList();
        if (cities.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // TODO(Phase B): EncryptionService.hmac(cityCode) で hash を算出し、
        // users.city_code_hash IN (:hashes) で検索する実装に差し替える。
        log.warn("REGION_CITY segment はデータソース未整備のため評価不能です。"
                        + "users.city_code / city_code_hash カラムへの"
                        + "データ投入 UI / API 整備を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.REGION_CITY,
                "users.city_code_hash (Phase B でデータ投入 UI 整備予定)");
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SEGMENT_VALUE_TYPE);
        } catch (Exception e) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID, e);
        }
    }
}
