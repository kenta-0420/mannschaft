package com.mannschaft.app.advertising.campaign.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * F09.17 メッセージ型キャンペーン Entity ⇔ DTO 変換マッパー。
 * 既存 {@code AdvertisingMapper} と同様に手書きスタイルで実装する。
 */
@Component
@RequiredArgsConstructor
public class AdMessagingCampaignMapper {

    private static final TypeReference<Map<String, Object>> SEGMENT_VALUE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * Entity → 一覧アイテムレスポンスに変換する。
     */
    public CampaignListItemResponse toListItem(AdMessagingCampaign entity) {
        return new CampaignListItemResponse(
                entity.getId(),
                entity.getName(),
                entity.getStatus(),
                entity.getModerationStatus(),
                entity.getTotalBudgetYen(),
                entity.getConsumedBudgetYen(),
                entity.getStartsAt(),
                entity.getEndsAt(),
                entity.getScheduledTimezone(),
                entity.getFrequencyCapOverride(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Entity と関連子レコードから詳細レスポンスを構築する。
     */
    public CampaignDetailResponse toDetail(
            AdMessagingCampaign entity,
            List<AdMessagingCampaignChannel> channels,
            List<AdAudienceSegment> segments) {
        return new CampaignDetailResponse(
                entity.getId(),
                entity.getAdvertiserAccountId(),
                entity.getName(),
                entity.getStatus(),
                entity.getModerationStatus(),
                entity.getBlockedReason(),
                entity.getTotalBudgetYen(),
                entity.getConsumedBudgetYen(),
                entity.getStartsAt(),
                entity.getEndsAt(),
                entity.getScheduledTimezone(),
                entity.getFrequencyCapOverride(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                channels.stream().map(this::toChannelResponse).toList(),
                segments.stream().map(this::toSegmentResponse).toList()
        );
    }

    /**
     * チャネル Entity → レスポンスに変換する。
     */
    public CampaignChannelResponse toChannelResponse(AdMessagingCampaignChannel entity) {
        return new CampaignChannelResponse(
                entity.getId(),
                entity.getCampaignId(),
                entity.getChannelType(),
                entity.getLocale(),
                entity.getSubject(),
                entity.getBodyMarkdown(),
                entity.getImageUrl(),
                entity.getCtaLabel(),
                entity.getCtaUrl(),
                entity.getBannerCreativeId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * セグメント Entity → レスポンスに変換する。
     * 格納されている JSON 文字列を {@code Map} にデシリアライズする。
     */
    public AudienceSegmentResponse toSegmentResponse(AdAudienceSegment entity) {
        return new AudienceSegmentResponse(
                entity.getId(),
                entity.getCampaignId(),
                entity.getSegmentType(),
                deserializeSegmentValue(entity.getSegmentValue()),
                entity.getInclusionMode(),
                entity.getCreatedAt()
        );
    }

    /**
     * セグメント条件 Map を JSON 文字列にシリアライズする。
     * 失敗時は {@link AdCampaignErrorCode#AD_AUDIENCE_INVALID} で 400 を返す。
     */
    public String serializeSegmentValue(Map<String, Object> segmentValue) {
        try {
            return objectMapper.writeValueAsString(segmentValue);
        } catch (JsonProcessingException e) {
            throw new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID, e);
        }
    }

    private Map<String, Object> deserializeSegmentValue(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SEGMENT_VALUE_TYPE);
        } catch (JsonProcessingException e) {
            // 不正な JSON が格納されていた場合でも詳細取得は壊さない（空 Map にフォールバック）
            return Map.of();
        }
    }
}
