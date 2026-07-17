package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * モジュールの有効/無効更新リクエスト（SYSTEM_ADMIN用）。
 * {@code PATCH /api/v1/system-admin/modules/{id}/active} のボディ。
 */
@Getter
@RequiredArgsConstructor
public class UpdateModuleActiveRequest {

    /** モジュールを有効化するか。 */
    @NotNull
    private final Boolean isActive;
}
