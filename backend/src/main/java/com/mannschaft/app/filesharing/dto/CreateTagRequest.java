package com.mannschaft.app.filesharing.dto;

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

    @NotBlank
    @Size(max = 50)
    private final String tagName;
}
