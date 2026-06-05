package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ記事作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateBlogPostRequest {

    /** チーム公開ID（UUIDv7文字列）または内部Long ID文字列。どちらも受け入れる後方互換方式。 */
    private final String teamId;
    /** 組織公開ID（UUIDv7文字列）または内部Long ID文字列。どちらも受け入れる後方互換方式。 */
    private final String organizationId;
    private final Long socialProfileId;

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

    private final String postType;
    private final String visibility;
    private final String priority;
    private final List<Long> tagIds;
    private final LocalDateTime publishedAt;
    private final LocalDateTime archiveAt;
    private final Boolean crossPostToTimeline;
    private final Long seriesId;
    private final Short seriesOrder;
}
