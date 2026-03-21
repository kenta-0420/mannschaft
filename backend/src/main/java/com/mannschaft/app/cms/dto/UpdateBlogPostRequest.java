package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ記事更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateBlogPostRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 200)
    private final String slug;

    @NotBlank
    @Size(max = 50000)
    private final String body;

    @Size(max = 500)
    private final String excerpt;

    @Size(max = 500)
    private final String coverImageUrl;

    private final String visibility;
    private final String priority;
    private final List<Long> tagIds;
    private final LocalDateTime archiveAt;
    private final Boolean crossPostToTimeline;
    private final Long seriesId;
    private final Short seriesOrder;
    private final Integer version;
}
