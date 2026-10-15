package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * リアクション追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddReactionRequest {

    @NotBlank
    @Size(max = 50)
    private final String emoji;
}
