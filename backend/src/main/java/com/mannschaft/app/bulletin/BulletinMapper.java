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
import java.util.stream.Collectors;

/**
 * 掲示板機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface BulletinMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    CategoryResponse toCategoryResponse(BulletinCategoryEntity entity);

    List<CategoryResponse> toCategoryResponseList(List<BulletinCategoryEntity> entities);

    /**
     * スレッドエンティティをネスト設計の ThreadResponse に変換する。
     * MapStruct の自動マッピングはフラット→ネスト構造では機能しないため、
     * default メソッドでビルダーを明示的に使用する。
     */
    default ThreadResponse toThreadResponse(BulletinThreadEntity entity) {
        if (entity == null) return null;
        return ThreadResponse.builder()
                .id(entity.getId())
                .scope(new ThreadResponse.ThreadScopeDto(
                        entity.getCategoryId(),
                        entity.getScopeType() != null ? entity.getScopeType().name() : null,
                        entity.getScopeId()))
                .content(new ThreadResponse.ThreadContentDto(
                        entity.getTitle(),
                        entity.getBody(),
                        entity.getPriority() != null ? entity.getPriority().name() : null,
                        entity.getReadTrackingMode() != null ? entity.getReadTrackingMode().name() : null))
                .state(new ThreadResponse.ThreadStateDto(
                        entity.getIsPinned(),
                        entity.getIsLocked(),
                        entity.getIsArchived(),
                        entity.getArchiveFolderId()))
                .stats(new ThreadResponse.ThreadStatsDto(
                        entity.getReplyCount(),
                        entity.getReadCount(),
                        entity.getLastRepliedAt()))
                .source(new ThreadResponse.ThreadSourceDto(
                        entity.getSourceType(),
                        entity.getSourceId()))
                .audit(new ThreadResponse.ThreadAuditDto(
                        entity.getAuthorId(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .build();
    }

    /**
     * スレッドエンティティのリストを ThreadResponse リストに変換する。
     */
    default List<ThreadResponse> toThreadResponseList(List<BulletinThreadEntity> entities) {
        if (entities == null) return Collections.emptyList();
        return entities.stream().map(this::toThreadResponse).collect(Collectors.toList());
    }

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
                entity.getDepth(),
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
