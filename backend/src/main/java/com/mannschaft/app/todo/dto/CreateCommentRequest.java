package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コメント作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCommentRequest {

    @NotBlank
    private final String body;
}
