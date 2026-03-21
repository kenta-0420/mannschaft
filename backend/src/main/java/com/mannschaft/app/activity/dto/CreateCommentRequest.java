package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コメント作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCommentRequest {

    @NotBlank
    @Size(max = 2000)
    private final String body;
}
