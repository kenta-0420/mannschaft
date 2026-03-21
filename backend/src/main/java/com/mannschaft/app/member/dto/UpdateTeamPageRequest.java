package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ページ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTeamPageRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    @Size(max = 200)
    private final String slug;

    private final String description;

    @Size(max = 500)
    private final String coverImageS3Key;

    private final String visibility;

    private final Boolean allowSelfEdit;

    private final Integer sortOrder;
}
