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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ChatMapperImpl implements ChatMapper {

    @Override
    public ChannelResponse toChannelResponse(ChatChannelEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String name = null;
        String iconKey = null;
        String description = null;
        Boolean isPrivate = null;
        Long createdBy = null;
        LocalDateTime lastMessageAt = null;
        String lastMessagePreview = null;
        String sourceType = null;
        Long sourceId = null;
        Boolean isArchived = null;
        Long version = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        name = entity.getName();
        iconKey = entity.getIconKey();
        description = entity.getDescription();
        isPrivate = entity.getIsPrivate();
        createdBy = entity.getCreatedBy();
        lastMessageAt = entity.getLastMessageAt();
        lastMessagePreview = entity.getLastMessagePreview();
        sourceType = entity.getSourceType();
        sourceId = entity.getSourceId();
        isArchived = entity.getIsArchived();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String channelType = entity.getChannelType().name();

        ChannelResponse channelResponse = new ChannelResponse( id, channelType, teamId, organizationId, name, iconKey, description, isPrivate, createdBy, lastMessageAt, lastMessagePreview, sourceType, sourceId, isArchived, version, createdAt, updatedAt );

        return channelResponse;
    }

    @Override
    public List<ChannelResponse> toChannelResponseList(List<ChatChannelEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ChannelResponse> list = new ArrayList<ChannelResponse>( entities.size() );
        for ( ChatChannelEntity chatChannelEntity : entities ) {
            list.add( toChannelResponse( chatChannelEntity ) );
        }

        return list;
    }

    @Override
    public MessageResponse toMessageResponse(ChatMessageEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long channelId = null;
        Long senderId = null;
        Long parentId = null;
        String body = null;
        Long forwardedFromId = null;
        Boolean isEdited = null;
        Boolean isSystem = null;
        LocalDateTime scheduledAt = null;
        Integer replyCount = null;
        Integer reactionCount = null;
        Boolean isPinned = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        channelId = entity.getChannelId();
        senderId = entity.getSenderId();
        parentId = entity.getParentId();
        body = entity.getBody();
        forwardedFromId = entity.getForwardedFromId();
        isEdited = entity.getIsEdited();
        isSystem = entity.getIsSystem();
        scheduledAt = entity.getScheduledAt();
        replyCount = entity.getReplyCount();
        reactionCount = entity.getReactionCount();
        isPinned = entity.getIsPinned();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        List<AttachmentResponse> attachments = null;
        List<ReactionResponse> reactions = null;

        MessageResponse messageResponse = new MessageResponse( id, channelId, senderId, parentId, body, forwardedFromId, isEdited, isSystem, scheduledAt, replyCount, reactionCount, isPinned, attachments, reactions, createdAt, updatedAt );

        return messageResponse;
    }

    @Override
    public List<MessageResponse> toMessageResponseList(List<ChatMessageEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MessageResponse> list = new ArrayList<MessageResponse>( entities.size() );
        for ( ChatMessageEntity chatMessageEntity : entities ) {
            list.add( toMessageResponse( chatMessageEntity ) );
        }

        return list;
    }

    @Override
    public MemberResponse toMemberResponse(ChatChannelMemberEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long channelId = null;
        Long userId = null;
        Integer unreadCount = null;
        LocalDateTime lastReadAt = null;
        Boolean isMuted = null;
        Boolean isPinned = null;
        String category = null;
        LocalDateTime joinedAt = null;

        id = entity.getId();
        channelId = entity.getChannelId();
        userId = entity.getUserId();
        unreadCount = entity.getUnreadCount();
        lastReadAt = entity.getLastReadAt();
        isMuted = entity.getIsMuted();
        isPinned = entity.getIsPinned();
        category = entity.getCategory();
        joinedAt = entity.getJoinedAt();

        String role = entity.getRole().name();

        MemberResponse memberResponse = new MemberResponse( id, channelId, userId, role, unreadCount, lastReadAt, isMuted, isPinned, category, joinedAt );

        return memberResponse;
    }

    @Override
    public List<MemberResponse> toMemberResponseList(List<ChatChannelMemberEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MemberResponse> list = new ArrayList<MemberResponse>( entities.size() );
        for ( ChatChannelMemberEntity chatChannelMemberEntity : entities ) {
            list.add( toMemberResponse( chatChannelMemberEntity ) );
        }

        return list;
    }

    @Override
    public ReactionResponse toReactionResponse(ChatMessageReactionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long messageId = null;
        Long userId = null;
        String emoji = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        messageId = entity.getMessageId();
        userId = entity.getUserId();
        emoji = entity.getEmoji();
        createdAt = entity.getCreatedAt();

        ReactionResponse reactionResponse = new ReactionResponse( id, messageId, userId, emoji, createdAt );

        return reactionResponse;
    }

    @Override
    public List<ReactionResponse> toReactionResponseList(List<ChatMessageReactionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReactionResponse> list = new ArrayList<ReactionResponse>( entities.size() );
        for ( ChatMessageReactionEntity chatMessageReactionEntity : entities ) {
            list.add( toReactionResponse( chatMessageReactionEntity ) );
        }

        return list;
    }

    @Override
    public AttachmentResponse toAttachmentResponse(ChatMessageAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long messageId = null;
        String fileKey = null;
        String fileName = null;
        Long fileSize = null;
        String contentType = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        messageId = entity.getMessageId();
        fileKey = entity.getFileKey();
        fileName = entity.getFileName();
        fileSize = entity.getFileSize();
        contentType = entity.getContentType();
        createdAt = entity.getCreatedAt();

        AttachmentResponse attachmentResponse = new AttachmentResponse( id, messageId, fileKey, fileName, fileSize, contentType, createdAt );

        return attachmentResponse;
    }

    @Override
    public List<AttachmentResponse> toAttachmentResponseList(List<ChatMessageAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttachmentResponse> list = new ArrayList<AttachmentResponse>( entities.size() );
        for ( ChatMessageAttachmentEntity chatMessageAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( chatMessageAttachmentEntity ) );
        }

        return list;
    }

    @Override
    public BookmarkResponse toBookmarkResponse(ChatMessageBookmarkEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long messageId = null;
        Long userId = null;
        String note = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        messageId = entity.getMessageId();
        userId = entity.getUserId();
        note = entity.getNote();
        createdAt = entity.getCreatedAt();

        BookmarkResponse bookmarkResponse = new BookmarkResponse( id, messageId, userId, note, createdAt );

        return bookmarkResponse;
    }

    @Override
    public List<BookmarkResponse> toBookmarkResponseList(List<ChatMessageBookmarkEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BookmarkResponse> list = new ArrayList<BookmarkResponse>( entities.size() );
        for ( ChatMessageBookmarkEntity chatMessageBookmarkEntity : entities ) {
            list.add( toBookmarkResponse( chatMessageBookmarkEntity ) );
        }

        return list;
    }
}
