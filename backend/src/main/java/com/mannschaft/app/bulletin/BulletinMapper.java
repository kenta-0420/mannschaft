package com.mannschaft.app.bulletin;

import com.mannschaft.app.bulletin.dto.AttachmentResponse;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.List;

/**
 * 掲示板機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface BulletinMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    CategoryResponse toCategoryResponse(BulletinCategoryEntity entity);

    List<CategoryResponse> toCategoryResponseList(List<BulletinCategoryEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "priority", expression = "java(entity.getPriority().name())")
    @Mapping(target = "readTrackingMode", expression = "java(entity.getReadTrackingMode().name())")
    ThreadResponse toThreadResponse(BulletinThreadEntity entity);

    List<ThreadResponse> toThreadResponseList(List<BulletinThreadEntity> entities);

    /**
     * 返信エンティティをレスポンスに変換する（子返信なし）。
     */
    default ReplyResponse toReplyResponse(BulletinReplyEntity entity) {
        return toReplyResponse(entity, Collections.emptyList());
    }

    /**
     * 返信エンティティを子返信付きレスポンスに変換する。
     */
    default ReplyResponse toReplyResponse(BulletinReplyEntity entity, List<ReplyResponse> children) {
        return new ReplyResponse(
                entity.getId(),
                entity.getThreadId(),
                entity.getParentId(),
                entity.getAuthorId(),
                entity.getBody(),
                entity.getIsEdited(),
                entity.getReplyCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                children
        );
    }

    ReadStatusResponse toReadStatusResponse(BulletinReadStatusEntity entity);

    List<ReadStatusResponse> toReadStatusResponseList(List<BulletinReadStatusEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    AttachmentResponse toAttachmentResponse(BulletinAttachmentEntity entity);

    List<AttachmentResponse> toAttachmentResponseList(List<BulletinAttachmentEntity> entities);

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    ReactionResponse toReactionResponse(BulletinReactionEntity entity);

    List<ReactionResponse> toReactionResponseList(List<BulletinReactionEntity> entities);
}
