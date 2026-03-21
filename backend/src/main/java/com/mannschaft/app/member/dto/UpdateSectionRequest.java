package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セクション更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSectionRequest {

    @Size(max = 200)
    private final String title;

    private final String content;

    @Size(max = 500)
    private final String imageS3Key;

    @Size(max = 200)
    private final String imageCaption;

    private final Integer sortOrder;
}
