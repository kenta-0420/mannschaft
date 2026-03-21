package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムラインリアクションリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReactionRequest {

    @NotBlank
    @Size(max = 10)
    private final String emoji;
}
