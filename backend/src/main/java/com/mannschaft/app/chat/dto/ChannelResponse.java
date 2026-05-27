package com.mannschaft.app.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * チャンネルレスポンスDTO。
 * 識別情報・メタ情報・設定・最終メッセージ・ソース・監査情報をネストで表現する。
 */
@Builder(toBuilder = true)
@Getter
public class ChannelResponse {

    private Long id;

    private ChannelIdentityDto identity;
    private ChannelMetaDto meta;
    private ChannelSettingsDto settings;
    private ChannelLastMessageDto lastMessage;
    private ChannelSourceDto source;
    private ChannelAuditDto audit;

    public record ChannelIdentityDto(
            String channelType,
            Long teamId,
            Long organizationId) {}

    public record ChannelMetaDto(
            String name,
            String iconKey,
            String description) {}

    public record ChannelSettingsDto(
            Boolean isPrivate,
            Boolean isInquiryChannel,
            Boolean isArchived,
            Long version) {}

    public record ChannelLastMessageDto(
            LocalDateTime lastMessageAt,
            String lastMessagePreview) {}

    public record ChannelSourceDto(
            String sourceType,
            Long sourceId) {}

    public record ChannelAuditDto(
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
