package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タグ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTagRequest {

    private final Long teamId;
    private final Long organizationId;

    @NotBlank
    @Size(max = 50)
    private final String name;

    @Size(max = 7)
    private final String color;
}
