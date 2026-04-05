package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モジュール有効/無効切替リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ToggleModuleRequest {

    @NotNull
    private final Long moduleId;

    private final boolean enabled;
}
