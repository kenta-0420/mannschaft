package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 議案コメント投稿リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCommentRequest {

    @NotBlank
    @Size(max = 1000)
    private final String body;
}
