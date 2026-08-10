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
 * F09.17 ORG_TYPE セグメント評価器。
 *
 * <p>{@code segment_value} は {@code {"templates": ["sports_football", "neighborhood_assoc"]}} の形式。
 * {@code teams.template} に該当するチームに所属するユーザーを {@code user_roles} 経由で抽出する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgTypeSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.ORG_TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        List<String> templates = validateAndResolveTemplates(segment);

        // teams.template に該当する team_id 群に所属するユーザーを user_roles から抽出。
        // クロスドメインだが SELECT のみで FK は使わない（CLAUDE.md 原則 1 準拠）。
        List<Long> ids = entityManager
                .createNativeQuery(
                        "SELECT DISTINCT ur.user_id FROM user_roles ur " +
                        "JOIN teams t ON t.id = ur.team_id " +
                        "JOIN users u ON u.id = ur.user_id " +
                        "WHERE t.template IN (:templates) " +
                        "  AND t.deleted_at IS NULL " +
                        "  AND u.deleted_at IS NULL " +
                        "  AND u.status = 'ACTIVE'")
                .setParameter("templates", templates)
                .getResultList();
        Set<Long> result = new HashSet<>(ids.size());
        for (Object raw : ids) {
            if (raw instanceof Number n) {
                result.add(n.longValue());
            }
        }
        return result;
    }

    @Override
    public long countUserIds(AdAudienceSegment segment) {
        List<String> templates = validateAndResolveTemplates(segment);

        // COUNT(DISTINCT ...) で件数のみ取得し、user_id 集合をアプリ層に展開しない。
        Object countResult = entityManager
                .createNativeQuery(
                        "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
                        "JOIN teams t ON t.id = ur.team_id " +
                        "JOIN users u ON u.id = ur.user_id " +
                        "WHERE t.template IN (:templates) " +
                        "  AND t.deleted_at IS NULL " +
                        "  AND u.deleted_at IS NULL " +
                        "  AND u.status = 'ACTIVE'")
                .setParameter("templates", templates)
                .getSingleResult();
        return ((Number) countResult).longValue();
    }

    /**
     * segment_value をバリデーションし、template リストへ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーションロジック。
     */
    private List<String> validateAndResolveTemplates(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object templatesObj = value.get("templates");
        if (!(templatesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("ORG_TYPE segment に templates 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        List<String> templates = rawList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        if (templates.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        return templates;
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
