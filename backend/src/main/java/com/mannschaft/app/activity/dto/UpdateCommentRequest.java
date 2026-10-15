package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コメント更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCommentRequest {

    @NotBlank
    @Size(max = 2000)
    private final String body;
}
