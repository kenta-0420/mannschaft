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

    /**
     * メッセージ種別（F04.12・設計書 §5）。{@code TEXT}（通常本文） / {@code INVITE_CARD}（招待カード）。
     * 全メッセージにトップレベルで付与する（既定 {@code TEXT}）。FE mapper がトップレベルを読む。
     */
    private String messageType;

    /**
     * 招待カードの描画ペイロード（F04.12・設計書 §5 inviteData）。
     * {@code messageType == INVITE_CARD} のときのみ非 null。通常メッセージ（TEXT）は null。
     */
    private InviteCardDto inviteData;

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

    /**
     * 招待カードの描画契約（F04.12・設計書 §5 inviteData）。
     * FE {@code BeInviteData} が読むフィールド名に一致させる（camelCase・D-12）。
     *
     * @param tokenId   招待トークン ID
     * @param token     承諾/辞退 API に渡す UUID
     * @param scopeType 招待先種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeId   招待先 ID
     * @param scopeName 招待先の表示名
     * @param status    導出済み表示状態（{@code PENDING} / {@code JOINED} / {@code EXPIRED} / {@code REVOKED}）
     * @param isTarget  呼出ユーザーが宛先本人か（true=参加/辞退活性、false=承諾待ち）
     * @param expiresAt 有効期限（ISO8601）
     */
    public record InviteCardDto(
            Long tokenId,
            String token,
            String scopeType,
            Long scopeId,
            String scopeName,
            String status,
            boolean isTarget,
            LocalDateTime expiresAt) {}
}
