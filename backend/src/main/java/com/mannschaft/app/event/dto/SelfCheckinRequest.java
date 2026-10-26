package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セルフチェックインリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SelfCheckinRequest {

    @NotBlank
    private final String qrToken;
}
