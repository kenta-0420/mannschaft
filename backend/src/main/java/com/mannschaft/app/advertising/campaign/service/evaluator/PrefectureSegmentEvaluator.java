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
 * F09.17 REGION_PREFECTURE セグメント評価器。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"codes": ["13", "14"]}}（JIS X 0401 都道府県コード）。</p>
 *
 * <p>{@code users.prefecture_code_hash} カラム（V68.002 追加、HMAC-SHA256 ブラインドインデックス）を
 * 使って SQL 1 クエリでターゲットユーザーを特定する。</p>
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
    private final EncryptionService encryptionService;
    private final UserRepository userRepository;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.REGION_PREFECTURE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return new HashSet<>(userRepository.findUserIdsByPrefectureCodeHashIn(hashes));
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> hashes = validateAndResolveHashes(segment);
        return userRepository.countUserIdsByPrefectureCodeHashIn(hashes);
    }

    /**
     * segment_value をバリデーションし、HMAC ブラインドインデックスのハッシュリストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーション・変換ロジック。
     */
    private List<String> validateAndResolveHashes(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object codesObj = value.get("codes");
        if (!(codesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("REGION_PREFECTURE segment に codes 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> codes = new ArrayList<>();
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
            codes.add(trimmed);
        }
        if (codes.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // segment_value: {"codes":["13","14"]}
        // prefecture_code は AES-256-GCM 暗号化済みのため HMAC ブラインドインデックス (prefecture_code_hash) で検索する
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
