package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.auth.util.DeviceType;
import com.mannschaft.app.auth.util.UserAgentParser;
import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F09.17 DEVICE セグメント評価器。
 *
 * <p>{@code segment_value} は {@code {"devices": ["MOBILE", "DESKTOP"]}} の形式。
 * 値は {@link DeviceType} の名前 (DESKTOP / MOBILE / TABLET / UNKNOWN) を受け付ける。</p>
 *
 * <h2>判定ロジック</h2>
 * <p>{@code push_subscriptions.user_agent} を {@link UserAgentParser} で解析し、
 * 各ユーザーが指定デバイスから少なくとも 1 度 push 購読を行っているかで判定する。
 * 1 ユーザーが PC とスマホ両方で購読していれば、いずれの絞り込みでもヒットする。</p>
 *
 * <h2>制約</h2>
 * <ul>
 *   <li>push_subscriptions を持たない（push 通知未設定の）ユーザーは {@link DeviceType#UNKNOWN}
 *       としても評価されない（行自体が存在しないため）。
 *       UNKNOWN を明示的に含める用途は非現実的なので許容する。</li>
 *   <li>UA 文字列をアプリ層でパースするため、O(購読数) のメモリ展開が発生する。
 *       将来 push_subscriptions に device_type カラムを追加して索引化する案を残しておく
 *       （TODO は設計書 §3.2 注記）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSegmentEvaluator implements AdSegmentEvaluator {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean supports(AdSegmentType type) {
        return type == AdSegmentType.DEVICE;
    }

    @Override
    public Set<Long> resolveUserIds(AdAudienceSegment segment) {
        Set<DeviceType> targetTypes = validateAndResolveTargetTypes(segment);
        return matchUserIds(targetTypes, segment);
    }

    /**
     * {@inheritDoc}
     *
     * <p>注意: DEVICE セグメントの判定は {@code push_subscriptions.user_agent} を
     * {@link UserAgentParser}（uap-java）でアプリ層パースする必要があり、SQL の COUNT
     * クエリ1本には還元できない。したがって本実装は {@link #resolveUserIds} と同じ行走査を
     * 行った上で件数のみ返す（結果集合は呼び出し元に返さず、user_id をメモリに保持する期間を
     * 最小化する点のみ改善する）。</p>
     */
    @Override
    public long countUserIds(AdAudienceSegment segment) {
        Set<DeviceType> targetTypes = validateAndResolveTargetTypes(segment);
        return matchUserIds(targetTypes, segment).size();
    }

    /**
     * segment_value をバリデーションし、対象 {@link DeviceType} 集合へ変換する。
     * {@link #resolveUserIds} / {@link #countUserIds} 共通のバリデーションロジック。
     */
    private Set<DeviceType> validateAndResolveTargetTypes(AdAudienceSegment segment) {
        Map<String, Object> value = deserialize(segment.getSegmentValue());
        Object devicesObj = value.get("devices");
        if (!(devicesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            log.warn("DEVICE segment に devices 配列がありません: campaignId={}, segmentId={}",
                    segment.getCampaignId(), segment.getId());
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }

        Set<DeviceType> targetTypes = new HashSet<>();
        for (Object raw : rawList) {
            if (!(raw instanceof String str) || str.isBlank()) {
                continue;
            }
            try {
                targetTypes.add(DeviceType.valueOf(str.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("DEVICE segment に不正な値が含まれています: value={}, campaignId={}",
                        str, segment.getCampaignId());
                throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID, e);
            }
        }
        if (targetTypes.isEmpty()) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID);
        }
        return targetTypes;
    }

    /**
     * push_subscriptions を走査し、対象デバイス種別に一致するユーザーIDを集約する。
     */
    private Set<Long> matchUserIds(Set<DeviceType> targetTypes, AdAudienceSegment segment) {
        // push_subscriptions の user_agent をアプリ層でパースして該当ユーザーを集約。
        // クロスドメイン (auth.push_subscriptions) だが SELECT のみで FK は使わない
        // （CLAUDE.md 原則 1 準拠）。
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery(
                        "SELECT DISTINCT ps.user_id, ps.user_agent " +
                        "FROM push_subscriptions ps " +
                        "JOIN users u ON u.id = ps.user_id " +
                        "WHERE u.deleted_at IS NULL " +
                        "  AND u.status = 'ACTIVE' " +
                        "  AND ps.user_agent IS NOT NULL")
                .getResultList();

        Set<Long> result = new HashSet<>();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            Long userId = ((Number) row[0]).longValue();
            String userAgent = (String) row[1];
            DeviceType detected = UserAgentParser.parse(userAgent).deviceType();
            if (targetTypes.contains(detected)) {
                result.add(userId);
            }
        }
        return result;
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
