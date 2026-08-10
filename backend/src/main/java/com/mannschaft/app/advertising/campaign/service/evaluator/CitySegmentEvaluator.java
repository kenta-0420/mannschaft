package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * <p>{@code users.city_code_hash} カラム（V68.002 追加、HMAC-SHA256 ブラインドインデックス）を
 * 使って SQL 1 クエリでターゲットユーザーを特定する。</p>
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
    private final EncryptionService encryptionService;
    private final UserRepository userRepository;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_CITY;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return new HashSet<>(userRepository.findUserIdsByCityCodeHashIn(hashes));
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return userRepository.countUserIdsByCityCodeHashIn(hashes);
    }

    /**
     * segment_value をバリデーションし、HMAC ブラインドインデックスのハッシュリストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーション・変換ロジック。
     */
    private List<String> validateAndResolveHashes(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object codesObj = value.get("codes");
        if (!(codesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_CITY segment に codes 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> codes = new ArrayList<>();
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
            codes.add(trimmed);
        }
        if (codes.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // segment_value: {"codes":["13113"]}
        // city_code は AES-256-GCM 暗号化済みのため HMAC ブラインドインデックス (city_code_hash) で検索する
        return codes.stream()
                .map(encryptionService::hmac)
                .toList();
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
