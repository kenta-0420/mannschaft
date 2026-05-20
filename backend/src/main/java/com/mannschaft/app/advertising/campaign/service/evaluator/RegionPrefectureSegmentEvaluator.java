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
 * F09.17 REGION_PREFECTURE セグメント評価器（Phase A スタブ）。
 *
 * <p>DB 基盤（users.prefecture_code_hash）は Phase A で整備済み。
 * ただし、ユーザーが都道府県を登録する UI / API（プロフィール更新経路）は
 * Phase B で実装予定のため、現時点で prefecture_code_hash にデータが存在しない。
 * Phase B 実装時に {@link SegmentDataSourceNotAvailableException} 箇所を
 * users.prefecture_code_hash IN (:hashes) クエリに差し替える。</p>
 *
 * <p>segment_value 形式: {@code {"prefectures": ["13", "14", "27"]}}</p>
 * <p>都道府県コードは JIS X 0401 に準拠（例: 東京都=13）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionPrefectureSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_PREFECTURE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object prefObj = value.get("prefectures");
        if (!(prefObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_PREFECTURE segment に prefectures 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> prefectures = rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isBlank())
                .toList();
        if (prefectures.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // TODO(Phase B): EncryptionService.hmac(prefectureCode) で hash を算出し、
        // users.prefecture_code_hash IN (:hashes) で検索する実装に差し替える。
        log.warn("REGION_PREFECTURE segment はデータソース未整備のため評価不能です。"
                        + "users.prefecture_code / prefecture_code_hash カラムへの"
                        + "データ投入 UI / API 整備を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.REGION_PREFECTURE,
                "users.prefecture_code_hash (Phase B でデータ投入 UI 整備予定)");
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
