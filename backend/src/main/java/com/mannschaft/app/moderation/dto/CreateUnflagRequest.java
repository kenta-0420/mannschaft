package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ヤバいやつ解除申請リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateUnflagRequest {

    @NotBlank
    @Size(max = 5000)
    private final String reason;
}
