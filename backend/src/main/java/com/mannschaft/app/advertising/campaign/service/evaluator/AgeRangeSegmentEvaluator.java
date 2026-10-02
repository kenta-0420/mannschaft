package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 AGE_RANGE セグメント評価器（Phase B 本実装）。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"min": 20, "max": 39}}（min &le; age &le; max の閉区間）。</p>
 *
 * <p>Phase B で {@code users.birth_year SMALLINT UNSIGNED NULL} カラムが追加されたため（V68.004）、
 * 年齢範囲を生年範囲に変換し {@code users.birth_year BETWEEN :minBirthYear AND :maxBirthYear} で
 * インデックス検索を行う。</p>
 *
 * <h2>年齢 → 生年の変換ルール</h2>
 * <ul>
 *   <li>min=20, max=39 → minBirthYear = currentYear - 39, maxBirthYear = currentYear - 20</li>
 *   <li>BETWEEN は両端を含む（&ge; minBirthYear AND &le; maxBirthYear）</li>
 * </ul>
 *
 * <h2>segment_value バリデーション</h2>
 * <p>不正な segment_value は {@code AD_AUDIENCE_INVALID} をスローする。</p>
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
    private final UserRepository userRepository;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.AGE_RANGE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        int[] birthYearRange = validateAndResolveBirthYearRange(segment);
        List<Long> userIds = userRepository.findUserIdsByBirthYearBetween(birthYearRange[0], birthYearRange[1]);
        log.debug("AGE_RANGE segment 評価完了: minBirthYear={}, maxBirthYear={}, matchedUserCount={}, campaignId={}",
                birthYearRange[0], birthYearRange[1], userIds.size(), segment.getCampaignId());
        return new HashSet<>(userIds);
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        int[] birthYearRange = validateAndResolveBirthYearRange(segment);
        return userRepository.countUserIdsByBirthYearBetween(birthYearRange[0], birthYearRange[1]);
    }

    /**
     * segment_value をバリデーションし、生年範囲 [minBirthYear, maxBirthYear] を返す。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーション・変換ロジック。
     */
    private int[] validateAndResolveBirthYearRange(AdAudienceSegment segment) {
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

        // 年齢 → 生年の変換（min/max どちらか片方が null の場合は全件相当の端点で補完）
        int currentYear = java.time.Year.now().getValue();
        int resolvedMin = (min != null) ? min : 0;
        int resolvedMax = (max != null) ? max : MAX_PLAUSIBLE_AGE;
        int minBirthYear = currentYear - resolvedMax;  // 39歳以下 → currentYear-39 以降生まれ
        int maxBirthYear = currentYear - resolvedMin;  // 20歳以上 → currentYear-20 以前生まれ
        return new int[] {minBirthYear, maxBirthYear};
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
