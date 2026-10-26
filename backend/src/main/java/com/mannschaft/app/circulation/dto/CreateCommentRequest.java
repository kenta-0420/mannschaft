package com.mannschaft.app.circulation.dto;

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
    @Size(max = 1000)
    private final String body;
}
