package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 議案リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class MotionRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    private final String requiredApproval;
}
