package com.mannschaft.app.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メッセージレスポンスDTO。
 * スレッド情報・コンテンツ・エンゲージメント・監査情報をネストで表現する。
 */
@Builder(toBuilder = true)
@Getter
public class MessageResponse {

    private Long id;
    private Long channelId;
    private Long senderId;

    /** 送信者の表示情報（表示名・アバター）。FE がメッセージ送信者名を描画するために付与する。 */
    private SenderDto sender;

    private MessageThreadDto thread;
    private MessageContentDto content;
    private MessageEngagementDto engagement;
    private MessageAuditDto audit;

    /**
     * 送信者の表示情報。
     *
     * @param id          送信者ユーザーID
     * @param displayName 表示名（{@link com.mannschaft.app.auth.entity.UserEntity#getDisplayName()}。null 時は "ユーザー"）
     * @param avatarUrl   アバターURL（無い場合は null）
     */
    public record SenderDto(
            Long id,
            String displayName,
            String avatarUrl) {}

    public record MessageThreadDto(
            Long parentId,
            Long rootId,
            int depth,
            boolean suggestBoardMigration) {}

    public record MessageContentDto(
            String body,
            Long forwardedFromId,
            Boolean isEdited,
            Boolean isSystem,
            LocalDateTime scheduledAt) {}

    public record MessageEngagementDto(
            Integer replyCount,
            Integer reactionCount,
            Boolean isPinned,
            List<AttachmentResponse> attachments,
            List<ReactionResponse> reactions) {}

    public record MessageAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
