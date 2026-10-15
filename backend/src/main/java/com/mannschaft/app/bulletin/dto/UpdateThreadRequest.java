package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スレッド更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateThreadRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    @Size(max = 20)
    private final String priority;
}
