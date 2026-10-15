package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.repository.UserInterestTagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 INTEREST_TAG セグメント評価器（Phase A 本実装）。
 *
 * <p>設計書 §3.2 例: {@code segment_value = {"tag_ids": ["sports_football", "neighborhood_event"]}}。</p>
 *
 * <p>Phase A で {@code user_interest_tags} テーブルが整備されたため、
 * タグ文字列を {@link EncryptionService#hmac(String)} でハッシュ化し、
 * {@code user_interest_tags.tag_hash IN (:hashes)} でブラインドインデックス検索を行う。</p>
 *
 * <p>ただし、ユーザーが興味タグを登録する UI / API は Phase B 以降で実装予定のため、
 * 現時点では {@code user_interest_tags} にデータが存在せず空集合が返る。
 * これは「データが無い」という正常状態であり、対処療法ではない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterestTagSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    /** タグ文字列の最大長（user_interest_tags.tag カラムに合わせる）。 */
    private static final int MAX_TAG_LENGTH = 50;

    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;
    private final UserInterestTagRepository userInterestTagRepository;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.INTEREST_TAG;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> tagHashes = validateAndResolveHashes(segment);
        List<Long> userIds = userInterestTagRepository.findUserIdsByTagHashIn(tagHashes);
        log.debug("INTEREST_TAG segment 評価完了: tagCount={}, matchedUserCount={}, campaignId={}",
                tagHashes.size(), userIds.size(), segment.getCampaignId());
        return new HashSet<>(userIds);
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> tagHashes = validateAndResolveHashes(segment);
        return userInterestTagRepository.countUserIdsByTagHashIn(tagHashes);
    }

    /**
     * segment_value をバリデーションし、HMAC ブラインドインデックスのハッシュリストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーション・変換ロジック。
     */
    private List<String> validateAndResolveHashes(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object tagsObj = value.get("tag_ids");
        if (!(tagsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("INTEREST_TAG segment に tag_ids 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> tags = rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        for (String tag : tags) {
            if (tag.length() > MAX_TAG_LENGTH) {
                log.warn("INTEREST_TAG segment の tag が長すぎます: length={}, campaignId={}",
                        tag.length(), segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
            }
        }
        if (tags.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        // タグを HMAC-SHA256 でハッシュ化してブラインドインデックス検索
        return tags.stream()
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
