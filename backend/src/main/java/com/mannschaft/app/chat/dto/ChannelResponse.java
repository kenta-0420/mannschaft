package com.mannschaft.app.chat.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * チャンネルレスポンスDTO。
 * 識別情報・メタ情報・設定・最終メッセージ・ソース・監査情報をネストで表現する。
 *
 * <p>per-user 拡張（2026-06-30 / チャンネル契約フル是正・第一陣）:</p>
 * <ul>
 *   <li>{@code memberCount} — チャンネルのメンバー総数。</li>
 *   <li>{@code dmPartner} — DM の場合の「呼出ユーザー以外の相手」。DM 以外は {@code null}。</li>
 *   <li>{@code viewer} — 呼出ユーザー自身のメンバー状態（未読・ミュート等）。非メンバー/未認証時は {@code null}。</li>
 * </ul>
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

    /** チャンネルのメンバー総数。 */
    private Integer memberCount;

    /** DM 相手（DM チャンネルのときのみ。DM 以外は null）。 */
    private DmPartnerDto dmPartner;

    /** 呼出ユーザー自身のメンバー状態（非メンバー/未認証時は null）。 */
    private ViewerStateDto viewer;

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

    /**
     * DM 相手の表示用情報。
     *
     * @param userId      相手ユーザーID（メンバー行の user_id）
     * @param displayName 相手の表示名（null の場合は "ユーザー" にフォールバック）
     * @param avatarUrl   相手のアバターURL（未設定なら null）
     */
    public record DmPartnerDto(
            Long userId,
            String displayName,
            String avatarUrl) {}

    /**
     * 呼出ユーザー自身のチャンネルメンバー状態。
     *
     * @param unreadCount 未読件数
     * @param isMuted     ミュート設定
     * @param isPinned    ピン留め設定
     * @param category    個人カテゴリ
     * @param role        チャンネル内ロール（OWNER / ADMIN / MEMBER 等）
     */
    public record ViewerStateDto(
            Integer unreadCount,
            Boolean isMuted,
            Boolean isPinned,
            String category,
            String role) {}
}
