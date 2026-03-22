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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class TimelineMapperImpl implements TimelineMapper {

    @Override
    public PostResponse toPostResponse(TimelinePostEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        Long userId = null;
        Long socialProfileId = null;
        Long postedAsId = null;
        Long parentId = null;
        String content = null;
        Long repostOfId = null;
        Integer repostCount = null;
        LocalDateTime scheduledAt = null;
        Boolean isPinned = null;
        Integer reactionCount = null;
        Integer replyCount = null;
        Short attachmentCount = null;
        Short editCount = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        userId = entity.getUserId();
        socialProfileId = entity.getSocialProfileId();
        postedAsId = entity.getPostedAsId();
        parentId = entity.getParentId();
        content = entity.getContent();
        repostOfId = entity.getRepostOfId();
        repostCount = entity.getRepostCount();
        scheduledAt = entity.getScheduledAt();
        isPinned = entity.getIsPinned();
        reactionCount = entity.getReactionCount();
        replyCount = entity.getReplyCount();
        attachmentCount = entity.getAttachmentCount();
        editCount = entity.getEditCount();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String postedAsType = entity.getPostedAsType().name();
        String status = entity.getStatus().name();

        PostResponse postResponse = new PostResponse( id, scopeType, scopeId, userId, socialProfileId, postedAsType, postedAsId, parentId, content, repostOfId, repostCount, status, scheduledAt, isPinned, reactionCount, replyCount, attachmentCount, editCount, createdAt, updatedAt );

        return postResponse;
    }

    @Override
    public List<PostResponse> toPostResponseList(List<TimelinePostEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PostResponse> list = new ArrayList<PostResponse>( entities.size() );
        for ( TimelinePostEntity timelinePostEntity : entities ) {
            list.add( toPostResponse( timelinePostEntity ) );
        }

        return list;
    }

    @Override
    public AttachmentResponse toAttachmentResponse(TimelinePostAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String fileKey = null;
        String originalFilename = null;
        Integer fileSize = null;
        String mimeType = null;
        Short imageWidth = null;
        Short imageHeight = null;
        String videoUrl = null;
        String videoThumbnailUrl = null;
        String videoTitle = null;
        String linkUrl = null;
        String ogTitle = null;
        String ogDescription = null;
        String ogImageUrl = null;
        String ogSiteName = null;
        Short sortOrder = null;

        id = entity.getId();
        fileKey = entity.getFileKey();
        originalFilename = entity.getOriginalFilename();
        fileSize = entity.getFileSize();
        mimeType = entity.getMimeType();
        imageWidth = entity.getImageWidth();
        imageHeight = entity.getImageHeight();
        videoUrl = entity.getVideoUrl();
        videoThumbnailUrl = entity.getVideoThumbnailUrl();
        videoTitle = entity.getVideoTitle();
        linkUrl = entity.getLinkUrl();
        ogTitle = entity.getOgTitle();
        ogDescription = entity.getOgDescription();
        ogImageUrl = entity.getOgImageUrl();
        ogSiteName = entity.getOgSiteName();
        sortOrder = entity.getSortOrder();

        String attachmentType = entity.getAttachmentType().name();

        AttachmentResponse attachmentResponse = new AttachmentResponse( id, attachmentType, fileKey, originalFilename, fileSize, mimeType, imageWidth, imageHeight, videoUrl, videoThumbnailUrl, videoTitle, linkUrl, ogTitle, ogDescription, ogImageUrl, ogSiteName, sortOrder );

        return attachmentResponse;
    }

    @Override
    public List<AttachmentResponse> toAttachmentResponseList(List<TimelinePostAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AttachmentResponse> list = new ArrayList<AttachmentResponse>( entities.size() );
        for ( TimelinePostAttachmentEntity timelinePostAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( timelinePostAttachmentEntity ) );
        }

        return list;
    }

    @Override
    public ReactionResponse toReactionResponse(TimelinePostReactionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long timelinePostId = null;
        Long userId = null;
        String emoji = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        timelinePostId = entity.getTimelinePostId();
        userId = entity.getUserId();
        emoji = entity.getEmoji();
        createdAt = entity.getCreatedAt();

        ReactionResponse reactionResponse = new ReactionResponse( id, timelinePostId, userId, emoji, createdAt );

        return reactionResponse;
    }

    @Override
    public List<ReactionResponse> toReactionResponseList(List<TimelinePostReactionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReactionResponse> list = new ArrayList<ReactionResponse>( entities.size() );
        for ( TimelinePostReactionEntity timelinePostReactionEntity : entities ) {
            list.add( toReactionResponse( timelinePostReactionEntity ) );
        }

        return list;
    }

    @Override
    public BookmarkResponse toBookmarkResponse(TimelineBookmarkEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long timelinePostId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        timelinePostId = entity.getTimelinePostId();
        createdAt = entity.getCreatedAt();

        BookmarkResponse bookmarkResponse = new BookmarkResponse( id, userId, timelinePostId, createdAt );

        return bookmarkResponse;
    }

    @Override
    public List<BookmarkResponse> toBookmarkResponseList(List<TimelineBookmarkEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BookmarkResponse> list = new ArrayList<BookmarkResponse>( entities.size() );
        for ( TimelineBookmarkEntity timelineBookmarkEntity : entities ) {
            list.add( toBookmarkResponse( timelineBookmarkEntity ) );
        }

        return list;
    }

    @Override
    public PollOptionResponse toPollOptionResponse(TimelinePollOptionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String optionText = null;
        Integer voteCount = null;
        Short sortOrder = null;

        id = entity.getId();
        optionText = entity.getOptionText();
        voteCount = entity.getVoteCount();
        sortOrder = entity.getSortOrder();

        PollOptionResponse pollOptionResponse = new PollOptionResponse( id, optionText, voteCount, sortOrder );

        return pollOptionResponse;
    }

    @Override
    public List<PollOptionResponse> toPollOptionResponseList(List<TimelinePollOptionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PollOptionResponse> list = new ArrayList<PollOptionResponse>( entities.size() );
        for ( TimelinePollOptionEntity timelinePollOptionEntity : entities ) {
            list.add( toPollOptionResponse( timelinePollOptionEntity ) );
        }

        return list;
    }

    @Override
    public MuteResponse toMuteResponse(UserMuteEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String mutedType = null;
        Long mutedId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        mutedType = entity.getMutedType();
        mutedId = entity.getMutedId();
        createdAt = entity.getCreatedAt();

        MuteResponse muteResponse = new MuteResponse( id, userId, mutedType, mutedId, createdAt );

        return muteResponse;
    }

    @Override
    public List<MuteResponse> toMuteResponseList(List<UserMuteEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<MuteResponse> list = new ArrayList<MuteResponse>( entities.size() );
        for ( UserMuteEntity userMuteEntity : entities ) {
            list.add( toMuteResponse( userMuteEntity ) );
        }

        return list;
    }
}
