package com.mannschaft.app.chat;

import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.BookmarkResponse;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * チャット機能の Entity → DTO 変換マッパー。
 *
 * <p>ChannelResponse・MessageResponse はネスト設計のため MapStruct の自動マッピングは使用せず、
 * default メソッドで手動マッピングする。</p>
 */
@Mapper(componentModel = "spring")
public interface ChatMapper {

    /** depth がこの値以上の場合、掲示板移行を促す */
    int BOARD_MIGRATION_SUGGEST_DEPTH = 10;

    /**
     * チャンネルエンティティをネスト設計の ChannelResponse に変換する。
     */
    default ChannelResponse toChannelResponse(ChatChannelEntity entity) {
        if (entity == null) return null;
        return ChannelResponse.builder()
                .id(entity.getId())
                .identity(new ChannelResponse.ChannelIdentityDto(
                        entity.getChannelType() != null ? entity.getChannelType().name() : null,
                        entity.getTeamId(),
                        entity.getOrganizationId()))
                .meta(new ChannelResponse.ChannelMetaDto(
                        entity.getName(), entity.getIconKey(), entity.getDescription()))
                .settings(new ChannelResponse.ChannelSettingsDto(
                        entity.getIsPrivate(), entity.getIsInquiryChannel(),
                        entity.getIsArchived(), entity.getVersion()))
                .lastMessage(new ChannelResponse.ChannelLastMessageDto(
                        entity.getLastMessageAt(), entity.getLastMessagePreview()))
                .source(new ChannelResponse.ChannelSourceDto(
                        entity.getSourceType(), entity.getSourceId()))
                .audit(new ChannelResponse.ChannelAuditDto(
                        entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    List<ChannelResponse> toChannelResponseList(List<ChatChannelEntity> entities);

    /**
     * メッセージエンティティをネスト設計の MessageResponse に変換する。
     * attachments/reactions は空リストで初期化する。
     */
    default MessageResponse toMessageResponse(ChatMessageEntity entity) {
        if (entity == null) return null;
        int depth = entity.getDepth() != null ? entity.getDepth() : 0;
        return MessageResponse.builder()
                .id(entity.getId())
                .channelId(entity.getChannelId())
                .senderId(entity.getSenderId())
                .thread(new MessageResponse.MessageThreadDto(
                        entity.getParentId(), entity.getRootId(),
                        depth, depth >= BOARD_MIGRATION_SUGGEST_DEPTH))
                .content(new MessageResponse.MessageContentDto(
                        entity.getBody(), entity.getForwardedFromId(),
                        entity.getIsEdited(), entity.getIsSystem(), entity.getScheduledAt()))
                .engagement(new MessageResponse.MessageEngagementDto(
                        entity.getReplyCount(), entity.getReactionCount(),
                        entity.getIsPinned(), List.of(), List.of()))
                .audit(new MessageResponse.MessageAuditDto(
                        entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    List<MessageResponse> toMessageResponseList(List<ChatMessageEntity> entities);

    @Mapping(target = "role", expression = "java(entity.getRole().name())")
    MemberResponse toMemberResponse(ChatChannelMemberEntity entity);

    List<MemberResponse> toMemberResponseList(List<ChatChannelMemberEntity> entities);

    ReactionResponse toReactionResponse(ChatMessageReactionEntity entity);

    List<ReactionResponse> toReactionResponseList(List<ChatMessageReactionEntity> entities);

    AttachmentResponse toAttachmentResponse(ChatMessageAttachmentEntity entity);

    List<AttachmentResponse> toAttachmentResponseList(List<ChatMessageAttachmentEntity> entities);

    BookmarkResponse toBookmarkResponse(ChatMessageBookmarkEntity entity);

    List<BookmarkResponse> toBookmarkResponseList(List<ChatMessageBookmarkEntity> entities);

    /**
     * メッセージエンティティに添付ファイルとリアクションを付与してレスポンスを構築する。
     * sender は付与しない（後方互換オーバーロード）。
     */
    default MessageResponse toMessageResponseWithDetails(
            ChatMessageEntity entity,
            List<AttachmentResponse> attachments,
            List<ReactionResponse> reactions) {
        return toMessageResponseWithDetails(entity, attachments, reactions, null);
    }

    /**
     * メッセージエンティティに添付ファイル・リアクション・送信者情報を付与してレスポンスを構築する。
     *
     * @param entity      メッセージエンティティ
     * @param attachments 添付ファイル一覧
     * @param reactions   リアクション一覧
     * @param sender      送信者の表示情報（表示名・アバター）。null 可
     * @return 送信者情報まで付与した MessageResponse
     */
    default MessageResponse toMessageResponseWithDetails(
            ChatMessageEntity entity,
            List<AttachmentResponse> attachments,
            List<ReactionResponse> reactions,
            MessageResponse.SenderDto sender) {
        MessageResponse base = toMessageResponse(entity);
        if (base == null) return null;
        return base.toBuilder()
                .sender(sender)
                .engagement(new MessageResponse.MessageEngagementDto(
                        base.getEngagement().replyCount(),
                        base.getEngagement().reactionCount(),
                        base.getEngagement().isPinned(),
                        attachments != null ? attachments : List.of(),
                        reactions != null ? reactions : List.of()))
                .build();
    }
}
