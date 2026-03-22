package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.entity.BlogTagEntity;
import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:08+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CmsMapperImpl implements CmsMapper {

    @Override
    public BlogPostResponse toBlogPostResponse(BlogPostEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        Long userId = null;
        Long authorId = null;
        String title = null;
        String slug = null;
        String body = null;
        String excerpt = null;
        String coverImageUrl = null;
        LocalDateTime publishedAt = null;
        Boolean pinned = null;
        Boolean allowComments = null;
        Integer viewCount = null;
        Short readingTimeMinutes = null;
        Integer version = null;
        Long seriesId = null;
        Short seriesOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        userId = entity.getUserId();
        authorId = entity.getAuthorId();
        title = entity.getTitle();
        slug = entity.getSlug();
        body = entity.getBody();
        excerpt = entity.getExcerpt();
        coverImageUrl = entity.getCoverImageUrl();
        publishedAt = entity.getPublishedAt();
        pinned = entity.getPinned();
        allowComments = entity.getAllowComments();
        viewCount = entity.getViewCount();
        readingTimeMinutes = entity.getReadingTimeMinutes();
        version = entity.getVersion();
        seriesId = entity.getSeriesId();
        seriesOrder = entity.getSeriesOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String postType = entity.getPostType().name();
        String visibility = entity.getVisibility().name();
        String priority = entity.getPriority().name();
        String status = entity.getStatus().name();
        List<BlogPostResponse.TagSummary> tags = java.util.Collections.emptyList();

        BlogPostResponse blogPostResponse = new BlogPostResponse( id, teamId, organizationId, userId, authorId, title, slug, body, excerpt, coverImageUrl, postType, visibility, priority, status, publishedAt, pinned, allowComments, viewCount, readingTimeMinutes, version, seriesId, seriesOrder, tags, createdAt, updatedAt );

        return blogPostResponse;
    }

    @Override
    public List<BlogPostResponse> toBlogPostResponseList(List<BlogPostEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BlogPostResponse> list = new ArrayList<BlogPostResponse>( entities.size() );
        for ( BlogPostEntity blogPostEntity : entities ) {
            list.add( toBlogPostResponse( blogPostEntity ) );
        }

        return list;
    }

    @Override
    public BlogTagResponse toBlogTagResponse(BlogTagEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String color = null;
        Integer sortOrder = null;
        Integer postCount = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        color = entity.getColor();
        sortOrder = entity.getSortOrder();
        postCount = entity.getPostCount();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        BlogTagResponse blogTagResponse = new BlogTagResponse( id, name, color, sortOrder, postCount, createdAt, updatedAt );

        return blogTagResponse;
    }

    @Override
    public List<BlogTagResponse> toBlogTagResponseList(List<BlogTagEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BlogTagResponse> list = new ArrayList<BlogTagResponse>( entities.size() );
        for ( BlogTagEntity blogTagEntity : entities ) {
            list.add( toBlogTagResponse( blogTagEntity ) );
        }

        return list;
    }

    @Override
    public BlogSeriesResponse toBlogSeriesResponse(BlogPostSeriesEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String name = null;
        String description = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        name = entity.getName();
        description = entity.getDescription();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        long postCount = 0L;

        BlogSeriesResponse blogSeriesResponse = new BlogSeriesResponse( id, teamId, organizationId, name, description, createdBy, postCount, createdAt, updatedAt );

        return blogSeriesResponse;
    }

    @Override
    public List<BlogSeriesResponse> toBlogSeriesResponseList(List<BlogPostSeriesEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BlogSeriesResponse> list = new ArrayList<BlogSeriesResponse>( entities.size() );
        for ( BlogPostSeriesEntity blogPostSeriesEntity : entities ) {
            list.add( toBlogSeriesResponse( blogPostSeriesEntity ) );
        }

        return list;
    }

    @Override
    public RevisionResponse toRevisionResponse(BlogPostRevisionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Integer revisionNumber = null;
        String title = null;
        Long editorId = null;
        String changeSummary = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        revisionNumber = entity.getRevisionNumber();
        title = entity.getTitle();
        editorId = entity.getEditorId();
        changeSummary = entity.getChangeSummary();
        createdAt = entity.getCreatedAt();

        RevisionResponse revisionResponse = new RevisionResponse( id, revisionNumber, title, editorId, changeSummary, createdAt );

        return revisionResponse;
    }

    @Override
    public List<RevisionResponse> toRevisionResponseList(List<BlogPostRevisionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<RevisionResponse> list = new ArrayList<RevisionResponse>( entities.size() );
        for ( BlogPostRevisionEntity blogPostRevisionEntity : entities ) {
            list.add( toRevisionResponse( blogPostRevisionEntity ) );
        }

        return list;
    }

    @Override
    public BlogSettingsResponse toBlogSettingsResponse(UserBlogSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Boolean selfReviewEnabled = null;
        LocalTime selfReviewStart = null;
        LocalTime selfReviewEnd = null;

        selfReviewEnabled = entity.getSelfReviewEnabled();
        selfReviewStart = entity.getSelfReviewStart();
        selfReviewEnd = entity.getSelfReviewEnd();

        BlogSettingsResponse blogSettingsResponse = new BlogSettingsResponse( selfReviewEnabled, selfReviewStart, selfReviewEnd );

        return blogSettingsResponse;
    }
}
