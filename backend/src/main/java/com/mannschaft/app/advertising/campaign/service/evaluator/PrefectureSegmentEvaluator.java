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
 * F09.17 REGION_PREFECTURE セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"codes": ["13", "14"]}}（JIS X 0401 都道府県コード）。</p>
 *
 * <h2>データソース未整備宣言</h2>
 * <p>ユーザーの居住地は現状 {@code users.postal_code} が AES-256-GCM 暗号化された
 * {@code VARBINARY} カラムであり、SQL で都道府県コードに展開して索引化することはできない。
 * {@code prefectures} / {@code cities} マスタテーブルは F08.1 で既に存在するため、後続フェーズで
 * {@code users.prefecture_code CHAR(2) INDEX} を追加すれば本評価器の {@code resolveUserIds} を
 * SQL 1 本に差し替えるだけで稼働可能。</p>
 *
 * <p>本評価器は登録だけ済ませ、評価時は {@link SegmentDataSourceNotAvailableException} を投げる
 * （対処療法の空集合返却は禁止 — CLAUDE.md「障害対応の原則 — 根治治療を徹底すること」）。</p>
 *
 * <h2>segment_value バリデーション</h2>
 * <p>UI から登録される段階では segment_value の妥当性（コード形式 = 2 桁数字、空配列でない 等）を
 * チェックして {@code AD_AUDIENCE_INVALID} で早期に弾く。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrefectureSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /** JIS X 0401 都道府県コードは 01〜47 の 2 桁数字。 */
    private static final Pattern PREFECTURE_CODE_PATTERN = Pattern.compile("^[0-9]{2}$");

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_PREFECTURE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object codesObj = value.get("codes");
        if (!(codesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_PREFECTURE segment に codes 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        Set<String> targets = new HashSet<>();
        for (Object raw : rawList) {
            if (!(raw instanceof String str) || str.isBlank()) {
                continue;
            }
            String trimmed = str.trim();
            if (!PREFECTURE_CODE_PATTERN.matcher(trimmed).matches()) {
                log.warn("REGION_PREFECTURE segment に不正なコード形式: value={}, campaignId={}",
                        str, segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
            int code = Integer.parseInt(trimmed);
            if (code < 1 || code > 47) {
                log.warn("REGION_PREFECTURE segment に存在しないコード: code={}, campaignId={}",
                        code, segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
            targets.add(trimmed);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        log.warn("REGION_PREFECTURE segment はデータソース未整備のため評価不能です。"
                        + "users.prefecture_code (暗号化 postal_code は索引不可) の追加を待ってください。"
                        + "campaignId={}, segmentId={}",
                segment.getCampaignId(), segment.getId());
        throw new SegmentDataSourceNotAvailableException(
                AdSegmentType.REGION_PREFECTURE,
                "users.prefecture_code CHAR(2) INDEX (暗号化 postal_code は索引不可)");
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
