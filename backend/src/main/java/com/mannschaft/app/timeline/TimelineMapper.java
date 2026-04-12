package com.mannschaft.app.timeline;

import com.mannschaft.app.timeline.dto.AttachmentResponse;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.dto.PollOptionResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.entity.TimelineBookmarkEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * タイムライン機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface TimelineMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "postedAsType", expression = "java(entity.getPostedAsType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    PostResponse toPostResponse(TimelinePostEntity entity);

    List<PostResponse> toPostResponseList(List<TimelinePostEntity> entities);

    @Mapping(target = "attachmentType", expression = "java(entity.getAttachmentType().name())")
    @Mapping(target = "videoProcessingStatus", expression = "java(entity.getVideoProcessingStatus() != null ? entity.getVideoProcessingStatus().name() : null)")
    AttachmentResponse toAttachmentResponse(TimelinePostAttachmentEntity entity);

    List<AttachmentResponse> toAttachmentResponseList(List<TimelinePostAttachmentEntity> entities);

    ReactionResponse toReactionResponse(TimelinePostReactionEntity entity);

    List<ReactionResponse> toReactionResponseList(List<TimelinePostReactionEntity> entities);

    BookmarkResponse toBookmarkResponse(TimelineBookmarkEntity entity);

    List<BookmarkResponse> toBookmarkResponseList(List<TimelineBookmarkEntity> entities);

    PollOptionResponse toPollOptionResponse(TimelinePollOptionEntity entity);

    List<PollOptionResponse> toPollOptionResponseList(List<TimelinePollOptionEntity> entities);

    MuteResponse toMuteResponse(UserMuteEntity entity);

    List<MuteResponse> toMuteResponseList(List<UserMuteEntity> entities);
}
