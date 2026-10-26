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
 * <p>{@code users.gender_hash} カラム（V68.002 追加、HMAC-SHA256 ブラインドインデックス）を使って
 * SQL 1 クエリでターゲットユーザーを特定する。</p>
 *
 * <p>構造バリデーション（"genders" 配列の存在、ENUM 値の妥当性）を先行実施し、
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
     */
    private static final Set<String> ALLOWED_GENDERS =
            Set.of("MALE", "FEMALE", "OTHER", "PREFER_NOT_TO_SAY");

    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;
    private final UserRepository userRepository;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.GENDER;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return new HashSet<>(userRepository.findUserIdsByGenderHashIn(hashes));
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return userRepository.countUserIdsByGenderHashIn(hashes);
    }

    /**
     * segment_value をバリデーションし、HMAC ブラインドインデックスのハッシュリストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーション・変換ロジック。
     */
    private List<String> validateAndResolveHashes(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object gendersObj = value.get("genders");
        if (!(gendersObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("GENDER segment に genders 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> genders = new java.util.ArrayList<>();
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
            genders.add(normalized);
        }
        if (genders.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // segment_value: {"genders":["MALE","FEMALE"]}
        // gender は AES-256-GCM 暗号化済みのため HMAC ブラインドインデックス (gender_hash) で検索する
        return genders.stream()
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
