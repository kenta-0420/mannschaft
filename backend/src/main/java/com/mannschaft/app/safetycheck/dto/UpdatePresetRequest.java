package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メッセージプリセット更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePresetRequest {

    @Size(max = 200)
    private final String body;

    private final Integer sortOrder;

    private final Boolean isActive;
}
