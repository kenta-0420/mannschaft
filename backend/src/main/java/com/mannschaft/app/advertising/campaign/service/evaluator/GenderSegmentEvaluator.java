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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 GENDER セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"genders": ["MALE", "FEMALE", "OTHER", "PREFER_NOT_TO_SAY"]}}。</p>
 *
 * <h2>データソース未整備宣言</h2>
 * <p>{@code users} テーブルには現状 {@code gender} カラムが存在しない。
 * 性別は機微情報のため、安易にカラム追加せず、別途プライバシー設計議論を経て
 * 例えば {@code user_demographics} 表（オプトイン制）として導入する想定。</p>
 *
 * <p>本評価器は登録だけ済ませ、評価時は {@link SegmentDataSourceNotAvailableException} を投げる
 * （対処療法の空集合返却は禁止 — CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。
 * 後続フェーズでカラム / 表が整備され次第、{@link #resolveUserIds(AdAudienceSegment)} を
 * 本実装に差し替えるだけで切替可能。</p>
 *
 * <p>構造バリデーション（"genders" 配列の存在、ENUM 値の妥当性）は本評価器でも実施し、
 * 不正な segment_value は早期に {@code AD_AUDIENCE_INVALID} で 400 に倒す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenderSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /**
     * 性別の許容値（オプトイン未回答含む）。
     * カラム追加時もこの集合に準拠する。
     */
    private static final Set<String> ALLOWED_GENDERS =
            Set.of("MALE", "FEMALE", "OTHER", "PREFER_NOT_TO_SAY");

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.GENDER;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object gendersObj = value.get("genders");
        if (!(gendersObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("GENDER segment に genders 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        Set<String> targets = new HashSet<>();
        for (Object raw : rawList) {
            if (!(raw instanceof String str) || str.isBlank()) {
                continue;
            }
            String normalized = str.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_GENDERS.contains(normalized)) {
                log.warn("GENDER segment に不正な値: value={}, campaignId={}",
                        str, segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
            targets.add(normalized);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        log.warn("GENDER segment はデータソース未整備のため評価不能です。"
                        + "users.gender (または user_demographics 表) の追加を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.GENDER,
                "users.gender (またはオプトイン user_demographics 表)");
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
