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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * CMS機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface CmsMapper {

    @Mapping(target = "scope", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostScopeDto(entity.getTeamId(), entity.getOrganizationId(), entity.getUserId(), entity.getAuthorId()))")
    @Mapping(target = "content", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostContentDto(entity.getTitle(), entity.getSlug(), entity.getBody(), entity.getExcerpt(), entity.getCoverImageUrl()))")
    @Mapping(target = "meta", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostMetaDto(entity.getPostType() != null ? entity.getPostType().name() : null, entity.getVisibility() != null ? entity.getVisibility().name() : null, entity.getPriority() != null ? entity.getPriority().name() : null, entity.getStatus() != null ? entity.getStatus().name() : null, entity.getPinned(), entity.getAllowComments()))")
    @Mapping(target = "series", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostSeriesDto(entity.getSeriesId(), entity.getSeriesOrder()))")
    @Mapping(target = "stats", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostStatisticsDto(entity.getViewCount(), entity.getReadingTimeMinutes(), false, 0))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostAuditDto(entity.getPublishedAt(), entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt()))")
    @Mapping(target = "tags", expression = "java(java.util.Collections.emptyList())")
    // accessState は可視性・課金の合成後に各サービスが設定するため、通常マッピングでは設定しない。
    @Mapping(target = "accessState", ignore = true)
    BlogPostResponse toBlogPostResponse(BlogPostEntity entity);

    List<BlogPostResponse> toBlogPostResponseList(List<BlogPostEntity> entities);

    BlogTagResponse toBlogTagResponse(BlogTagEntity entity);

    List<BlogTagResponse> toBlogTagResponseList(List<BlogTagEntity> entities);

    @Mapping(target = "postCount", constant = "0L")
    BlogSeriesResponse toBlogSeriesResponse(BlogPostSeriesEntity entity);

    List<BlogSeriesResponse> toBlogSeriesResponseList(List<BlogPostSeriesEntity> entities);

    RevisionResponse toRevisionResponse(BlogPostRevisionEntity entity);

    List<RevisionResponse> toRevisionResponseList(List<BlogPostRevisionEntity> entities);

    BlogSettingsResponse toBlogSettingsResponse(UserBlogSettingsEntity entity);
}
