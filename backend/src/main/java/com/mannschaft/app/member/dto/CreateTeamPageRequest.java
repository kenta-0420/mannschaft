package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ページ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTeamPageRequest {

    private final Long teamId;

    private final Long organizationId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    @Size(max = 200)
    private final String slug;

    @NotBlank
    private final String pageType;

    private final Short year;

    private final String description;

    @Size(max = 500)
    private final String coverImageS3Key;

    private final String visibility;
}
