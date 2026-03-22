package com.mannschaft.app.bulletin;

import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
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
public class BulletinMapperImpl implements BulletinMapper {

    @Override
    public CategoryResponse toCategoryResponse(BulletinCategoryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String name = null;
        String description = null;
        Integer displayOrder = null;
        String color = null;
        String postMinRole = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        name = entity.getName();
        description = entity.getDescription();
        displayOrder = entity.getDisplayOrder();
        color = entity.getColor();
        postMinRole = entity.getPostMinRole();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();

        CategoryResponse categoryResponse = new CategoryResponse( id, scopeType, scopeId, name, description, displayOrder, color, postMinRole, createdBy, createdAt, updatedAt );

        return categoryResponse;
    }

    @Override
    public List<CategoryResponse> toCategoryResponseList(List<BulletinCategoryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CategoryResponse> list = new ArrayList<CategoryResponse>( entities.size() );
        for ( BulletinCategoryEntity bulletinCategoryEntity : entities ) {
            list.add( toCategoryResponse( bulletinCategoryEntity ) );
        }

        return list;
    }

    @Override
    public ThreadResponse toThreadResponse(BulletinThreadEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long categoryId = null;
        Long scopeId = null;
        Long authorId = null;
        String title = null;
        String body = null;
        Boolean isPinned = null;
        Boolean isLocked = null;
        Boolean isArchived = null;
        Integer replyCount = null;
        Integer readCount = null;
        LocalDateTime lastRepliedAt = null;
        String sourceType = null;
        Long sourceId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        categoryId = entity.getCategoryId();
        scopeId = entity.getScopeId();
        authorId = entity.getAuthorId();
        title = entity.getTitle();
        body = entity.getBody();
        isPinned = entity.getIsPinned();
        isLocked = entity.getIsLocked();
        isArchived = entity.getIsArchived();
        replyCount = entity.getReplyCount();
        readCount = entity.getReadCount();
        lastRepliedAt = entity.getLastRepliedAt();
        sourceType = entity.getSourceType();
        sourceId = entity.getSourceId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String priority = entity.getPriority().name();
        String readTrackingMode = entity.getReadTrackingMode().name();

        ThreadResponse threadResponse = new ThreadResponse( id, categoryId, scopeType, scopeId, authorId, title, body, priority, readTrackingMode, isPinned, isLocked, isArchived, replyCount, readCount, lastRepliedAt, sourceType, sourceId, createdAt, updatedAt );

        return threadResponse;
    }

    @Override
    public List<ThreadResponse> toThreadResponseList(List<BulletinThreadEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ThreadResponse> list = new ArrayList<ThreadResponse>( entities.size() );
        for ( BulletinThreadEntity bulletinThreadEntity : entities ) {
            list.add( toThreadResponse( bulletinThreadEntity ) );
        }

        return list;
    }

    @Override
    public ReadStatusResponse toReadStatusResponse(BulletinReadStatusEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long threadId = null;
        Long userId = null;
        LocalDateTime readAt = null;

        id = entity.getId();
        threadId = entity.getThreadId();
        userId = entity.getUserId();
        readAt = entity.getReadAt();

        ReadStatusResponse readStatusResponse = new ReadStatusResponse( id, threadId, userId, readAt );

        return readStatusResponse;
    }

    @Override
    public List<ReadStatusResponse> toReadStatusResponseList(List<BulletinReadStatusEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReadStatusResponse> list = new ArrayList<ReadStatusResponse>( entities.size() );
        for ( BulletinReadStatusEntity bulletinReadStatusEntity : entities ) {
            list.add( toReadStatusResponse( bulletinReadStatusEntity ) );
        }

        return list;
    }

    @Override
    public AttachmentResponse toAttachmentResponse(BulletinAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long targetId = null;
        String fileKey = null;
        String originalFilename = null;
        Long fileSize = null;
        String contentType = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        targetId = entity.getTargetId();
        fileKey = entity.getFileKey();
        originalFilename = entity.getOriginalFilename();
        fileSize = entity.getFileSize();
        contentType = entity.getContentType();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();

        String targetType = entity.getTargetType().name();

        AttachmentResponse attachmentResponse = new AttachmentResponse( id, targetType, targetId, fileKey, originalFilename, fileSize, contentType, createdBy, createdAt );

        return attachmentResponse;
    }

    @Override
    public List<AttachmentResponse> toAttachmentResponseList(List<BulletinAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttachmentResponse> list = new ArrayList<AttachmentResponse>( entities.size() );
        for ( BulletinAttachmentEntity bulletinAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( bulletinAttachmentEntity ) );
        }

        return list;
    }

    @Override
    public ReactionResponse toReactionResponse(BulletinReactionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long targetId = null;
        Long userId = null;
        String emoji = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        targetId = entity.getTargetId();
        userId = entity.getUserId();
        emoji = entity.getEmoji();
        createdAt = entity.getCreatedAt();

        String targetType = entity.getTargetType().name();

        ReactionResponse reactionResponse = new ReactionResponse( id, targetType, targetId, userId, emoji, createdAt );

        return reactionResponse;
    }

    @Override
    public List<ReactionResponse> toReactionResponseList(List<BulletinReactionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReactionResponse> list = new ArrayList<ReactionResponse>( entities.size() );
        for ( BulletinReactionEntity bulletinReactionEntity : entities ) {
            list.add( toReactionResponse( bulletinReactionEntity ) );
        }

        return list;
    }
}
