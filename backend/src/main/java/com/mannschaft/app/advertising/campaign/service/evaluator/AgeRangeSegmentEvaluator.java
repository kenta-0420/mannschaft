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

import java.util.Map;
import java.util.Set;

/**
 * F09.17 AGE_RANGE セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"min": 20, "max": 39}}（半開閉区間 min &le; age &le; max）。</p>
 *
 * <h2>データソース未整備宣言</h2>
 * <p>{@code users.birth_date} は AES-256-GCM 暗号化された {@code VARBINARY(255)} カラムであり、
 * SQL レイヤーで誕生年で絞り込むインデックスは <b>存在しない</b>。アプリ層で全 ACTIVE ユーザーを
 * load して復号 → 年齢計算という処理は 1000 万ユーザー規模では不可。</p>
 *
 * <p>そのため AGE_RANGE は <b>「シャーディング前提の年齢索引カラム追加」</b> を待って実装する。
 * 候補となるスキーマ追加（後続フェーズで Flyway 起票予定）:</p>
 * <ul>
 *   <li>{@code users.birth_year SMALLINT INDEX} — 西暦のみ（PII 配慮）</li>
 *   <li>または {@code user_demographics} 専用テーブル (シャーディング時に集約)</li>
 * </ul>
 *
 * <p>本評価器は登録だけ済ませ、評価時は {@link SegmentDataSourceNotAvailableException} を投げる
 * （対処療法の空集合返却は禁止 — CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。</p>
 *
 * <h2>segment_value バリデーションは継続実施</h2>
 * <p>UI 側からの不正値で 500 にならないよう、JSON 構造のバリデーション自体は本評価器でも行う。
 * 不正な segment_value → {@code AD_AUDIENCE_INVALID}, 構造が正しい → データソース未整備例外。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgeRangeSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /** 数値として許容する人類の年齢上限。これ以上は形式エラー扱い。 */
    private static final int MAX_PLAUSIBLE_AGE = 130;

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.AGE_RANGE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        // 構造バリデーション（不正 segment_value は早期に 400 で返したい）
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Integer min = parseAge(value.get("min"));
        Integer max = parseAge(value.get("max"));
        if (min == null && max == null) {
            log.warn("AGE_RANGE segment に min/max のいずれも欠落: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        if (min != null && max != null && min > max) {
            log.warn("AGE_RANGE segment の min > max: min={}, max={}, campaignId={}",
                    min, max, segment.getCampaignId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        if ((min != null && (min < 0 || min > MAX_PLAUSIBLE_AGE))
                || (max != null && (max < 0 || max > MAX_PLAUSIBLE_AGE))) {
            log.warn("AGE_RANGE segment の年齢が許容範囲外: min={}, max={}, campaignId={}",
                    min, max, segment.getCampaignId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        log.warn("AGE_RANGE segment はデータソース未整備のため評価不能です。"
                        + "users.birth_year (または user_demographics 表) の追加を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.AGE_RANGE,
                "users.birth_year インデックス (暗号化 birth_date は索引不可)");
    }

    private Integer parseAge(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID, e);
            }
        }
        throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
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
