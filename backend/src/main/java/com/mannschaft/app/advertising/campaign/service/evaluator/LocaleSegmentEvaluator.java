package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 LOCALE セグメント評価器。
 *
 * <p>{@code segment_value} に {@code {"locales": ["ja", "en"]}} の形式で言語タグ集合が
 * 入っている前提。{@code users.locale} カラムに対する IN 検索を発行する。</p>
 *
 * <p>{@code users.locale} は暗号化されておらず NOT NULL カラムなので SQL レベルで効率的にフィルタできる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocaleSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.LOCALE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> locales = validateAndResolveLocales(segment);
        List<Long> ids = entityManager
                .createQuery(
                        "SELECT u.id FROM UserEntity u " +
                        "WHERE u.locale IN :locales " +
                        "AND u.deletedAt IS NULL " +
                        "AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE",
                        Long.class)
                .setParameter("locales", locales)
                .getResultList();
        return new HashSet<>(ids);
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> locales = validateAndResolveLocales(segment);
        return entityManager
                .createQuery(
                        "SELECT COUNT(u.id) FROM UserEntity u " +
                        "WHERE u.locale IN :locales " +
                        "AND u.deletedAt IS NULL " +
                        "AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE",
                        Long.class)
                .setParameter("locales", locales)
                .getSingleResult();
    }

    /**
     * segment_value をバリデーションし、locale リストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーションロジック。
     */
    private List<String> validateAndResolveLocales(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object localesObj = value.get("locales");
        if (!(localesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("LOCALE segment に locales 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> locales = rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        if (locales.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        return locales;
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
