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
 */
@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(target = "channelType", expression = "java(entity.getChannelType().name())")
    ChannelResponse toChannelResponse(ChatChannelEntity entity);

    List<ChannelResponse> toChannelResponseList(List<ChatChannelEntity> entities);

    @Mapping(target = "attachments", ignore = true)
    @Mapping(target = "reactions", ignore = true)
    MessageResponse toMessageResponse(ChatMessageEntity entity);

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
     */
    default MessageResponse toMessageResponseWithDetails(
            ChatMessageEntity entity,
            List<AttachmentResponse> attachments,
            List<ReactionResponse> reactions) {
        return new MessageResponse(
                entity.getId(),
                entity.getChannelId(),
                entity.getSenderId(),
                entity.getParentId(),
                entity.getBody(),
                entity.getForwardedFromId(),
                entity.getIsEdited(),
                entity.getIsSystem(),
                entity.getScheduledAt(),
                entity.getReplyCount(),
                entity.getReactionCount(),
                entity.getIsPinned(),
                attachments,
                reactions,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
