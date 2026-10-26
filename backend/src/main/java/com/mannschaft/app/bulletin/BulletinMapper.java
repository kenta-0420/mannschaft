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
     * スレッドエンティティをフラット設計の ThreadResponse に変換する（基底変換）。
     *
     * <p>FE 型 {@code BulletinThreadResponse}（フラット）を正準とし、それに一致させる。
     * MapStruct の自動マッピングはここでは使わず、default メソッドでビルダーを明示的に使用する。</p>
     *
     * <p>enrichment 5 項目（投稿者表示名/アバター・カテゴリ名/色・既読・リアクション集計）は
     * このメソッドでは解決しない（{@code author.displayName/avatarUrl=null}、{@code categoryName/Color=null}、
     * {@code isRead=false}、{@code reactionSummary={}}、{@code myReactions=[]}）。
     * これらは {@code BulletinThreadService#enrichThreads} がバッチ解決して上書きする。</p>
     */
    default ThreadResponse toThreadResponse(BulletinThreadEntity entity) {
        if (entity == null) return null;
        return ThreadResponse.builder()
                .id(entity.getId())
                .categoryId(entity.getCategoryId())
                .categoryName(null)
                .categoryColor(null)
                .scopeType(entity.getScopeType() != null ? entity.getScopeType().name() : null)
                .scopeId(entity.getScopeId())
                .author(new ThreadResponse.AuthorDto(entity.getAuthorId(), null, null))
                .title(entity.getTitle())
                .body(entity.getBody())
                .priority(entity.getPriority() != null ? entity.getPriority().name() : null)
                .readTrackingMode(entity.getReadTrackingMode() != null ? entity.getReadTrackingMode().name() : null)
                .isPinned(entity.getIsPinned())
                .isLocked(entity.getIsLocked())
                .isArchived(entity.getIsArchived())
                .archiveFolderId(entity.getArchiveFolderId())
                .replyCount(entity.getReplyCount())
                .readCount(entity.getReadCount())
                .isRead(false)
                .reactionSummary(java.util.Collections.emptyMap())
                .myReactions(java.util.Collections.emptyList())
                .lastRepliedAt(entity.getLastRepliedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
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
