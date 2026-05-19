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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F09.17 REGION_CITY セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"codes": ["13113", "13104"]}}（JIS X 0402 全国地方公共団体コード 5 桁）。</p>
 *
 * <h2>データソース未整備宣言</h2>
 * <p>{@link PrefectureSegmentEvaluator} と同様、ユーザー側 {@code users.city_code} カラムが未整備のため、
 * 後続フェーズで {@code users.city_code CHAR(5) INDEX} を追加して初めて稼働可能になる。
 * {@code cities} マスタテーブルは F08.1 で既に存在する。</p>
 *
 * <p>本評価器は登録だけ済ませ、評価時は {@link SegmentDataSourceNotAvailableException} を投げる
 * （対処療法の空集合返却は禁止 — CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CitySegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /** JIS X 0402 全国地方公共団体コード（5 桁）。 */
    private static final Pattern CITY_CODE_PATTERN = Pattern.compile("^[0-9]{5}$");

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_CITY;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object codesObj = value.get("codes");
        if (!(codesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_CITY segment に codes 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        Set<String> targets = new HashSet<>();
        for (Object raw : rawList) {
            if (!(raw instanceof String str) || str.isBlank()) {
                continue;
            }
            String trimmed = str.trim();
            if (!CITY_CODE_PATTERN.matcher(trimmed).matches()) {
                log.warn("REGION_CITY segment に不正なコード形式: value={}, campaignId={}",
                        str, segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
            targets.add(trimmed);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        log.warn("REGION_CITY segment はデータソース未整備のため評価不能です。"
                        + "users.city_code (暗号化 postal_code は索引不可) の追加を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.REGION_CITY,
                "users.city_code CHAR(5) INDEX (暗号化 postal_code は索引不可)");
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
