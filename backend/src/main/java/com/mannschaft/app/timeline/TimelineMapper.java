package com.mannschaft.app.timeline;

import com.mannschaft.app.timeline.dto.AttachmentResponse;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.dto.PollOptionResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import org.mapstruct.Mapper;

import java.util.Collections;
import java.util.List;

/**
 * タイムライン機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface TimelineMapper {

    default PostResponse toPostResponse(TimelinePostEntity entity) {
        if (entity == null) {
            return null;
        }
        return PostResponse.builder()
                .id(entity.getId())
                .scope(new PostResponse.PostScopeDto(
                        entity.getScopeType().name(),
                        entity.getScopeId()))
                .author(new PostResponse.PostAuthorDto(
                        entity.getUserId(),
                        entity.getSocialProfileId(),
                        entity.getPostedAsType().name(),
                        entity.getPostedAsId()))
                .content(new PostResponse.PostContentDto(
                        entity.getContent(),
                        entity.getParentId(),
                        entity.getRepostOfId(),
                        entity.getStatus().name(),
                        entity.getScheduledAt(),
                        entity.getIsPinned()))
                .stats(new PostResponse.PostStatsDto(
                        entity.getRepostCount(),
                        entity.getReactionCount(),
                        entity.getReplyCount(),
                        entity.getAttachmentCount(),
                        entity.getEditCount()))
                // フィード一覧では添付ファイル詳細は省略して空リストを返す
                // （詳細は getPostDetail で取得する。FE が attachments.length を参照するためnullは不可）
                .attachments(Collections.emptyList())
                .audit(new PostResponse.PostAuditDto(
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .build();
    }

    default List<PostResponse> toPostResponseList(List<TimelinePostEntity> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream().map(this::toPostResponse).toList();
    }

    default AttachmentResponse toAttachmentResponse(TimelinePostAttachmentEntity entity) {
        if (entity == null) {
            return null;
        }
        return AttachmentResponse.builder()
                .id(entity.getId())
                .attachmentType(entity.getAttachmentType().name())
                .file(new AttachmentResponse.AttachmentFileDto(
                        entity.getFileKey(),
                        entity.getOriginalFilename(),
                        entity.getFileSize(),
                        entity.getMimeType()))
                .image(new AttachmentResponse.AttachmentImageDto(
                        entity.getImageWidth(),
                        entity.getImageHeight()))
                .video(new AttachmentResponse.AttachmentVideoDto(
                        entity.getVideoUrl(),
                        entity.getVideoThumbnailUrl(),
                        entity.getVideoTitle(),
                        entity.getVideoThumbnailKey(),
                        entity.getVideoDurationSeconds(),
                        entity.getVideoCodec(),
                        entity.getVideoWidth(),
                        entity.getVideoHeight(),
                        entity.getVideoProcessingStatus() != null ? entity.getVideoProcessingStatus().name() : null))
                .link(new AttachmentResponse.AttachmentLinkDto(
                        entity.getLinkUrl(),
                        entity.getOgTitle(),
                        entity.getOgDescription(),
                        entity.getOgImageUrl(),
                        entity.getOgSiteName()))
                .sortOrder(entity.getSortOrder())
                .build();
    }

    default List<AttachmentResponse> toAttachmentResponseList(List<TimelinePostAttachmentEntity> entities) {
        if (entities == null) {
            return null;
        }
        return entities.stream().map(this::toAttachmentResponse).toList();
    }

    BookmarkResponse toBookmarkResponse(TimelineBookmarkEntity entity);

    List<BookmarkResponse> toBookmarkResponseList(List<TimelineBookmarkEntity> entities);

    PollOptionResponse toPollOptionResponse(TimelinePollOptionEntity entity);

    List<PollOptionResponse> toPollOptionResponseList(List<TimelinePollOptionEntity> entities);

    MuteResponse toMuteResponse(UserMuteEntity entity);

    List<MuteResponse> toMuteResponseList(List<UserMuteEntity> entities);
}
