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

/**
 * F09.17 INTEREST_TAG セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"tag_ids": ["sports_football", "neighborhood_event"]}}。</p>
 *
 * <h2>データソース未整備宣言</h2>
 * <p>{@code user_interest_tags} 表 / {@code interest_tags} マスタは現状未作成。
 * 後続フェーズで以下のスキーマを Flyway で追加すれば、本評価器の {@code resolveUserIds} を
 * SQL 1 本に差し替えるだけで稼働可能。</p>
 *
 * <pre>
 * CREATE TABLE interest_tags (
 *     id          VARCHAR(60) NOT NULL,
 *     name_ja     VARCHAR(60) NOT NULL,
 *     deleted_at  DATETIME    NULL,
 *     PRIMARY KEY (id)
 * );
 *
 * CREATE TABLE user_interest_tags (
 *     user_id   BIGINT      NOT NULL,
 *     tag_id    VARCHAR(60) NOT NULL,
 *     created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     PRIMARY KEY (user_id, tag_id),
 *     INDEX idx_user_interest_tags_tag (tag_id, user_id)
 * );
 * </pre>
 *
 * <p>本評価器は登録だけ済ませ、評価時は {@link SegmentDataSourceNotAvailableException} を投げる
 * （対処療法の空集合返却は禁止 — CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterestTagSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /** タグ ID の最大長（user_interest_tags.tag_id 想定）。 */
    private static final int MAX_TAG_ID_LENGTH = 60;

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.INTEREST_TAG;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object tagsObj = value.get("tag_ids");
        if (!(tagsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("INTEREST_TAG segment に tag_ids 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        Set<String> targets = new HashSet<>();
        for (Object raw : rawList) {
            if (!(raw instanceof String str) || str.isBlank()) {
                continue;
            }
            String trimmed = str.trim();
            if (trimmed.length() > MAX_TAG_ID_LENGTH) {
                log.warn("INTEREST_TAG segment の tag_id が長すぎます: length={}, campaignId={}",
                        trimmed.length(), segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
            targets.add(trimmed);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        log.warn("INTEREST_TAG segment はデータソース未整備のため評価不能です。"
                        + "user_interest_tags 表 / interest_tags マスタの追加を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.INTEREST_TAG,
                "user_interest_tags 表 + interest_tags マスタ");
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
