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

    @Mapping(target = "postType", expression = "java(entity.getPostType().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "priority", expression = "java(entity.getPriority().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "tags", expression = "java(java.util.Collections.emptyList())")
    @Mapping(target = "mitayo", constant = "false")
    @Mapping(target = "mitayoCount", constant = "0")
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
