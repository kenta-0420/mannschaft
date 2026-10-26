package com.mannschaft.app.notification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PreferenceUpdateRequest {

    private final String scopeType;

    private final Long scopeId;

    @NotNull
    private final Boolean isEnabled;
}
