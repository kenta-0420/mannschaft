package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コメント更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCommentRequest {

    @NotBlank
    private final String body;
}
