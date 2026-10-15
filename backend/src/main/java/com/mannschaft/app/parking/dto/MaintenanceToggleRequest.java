package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンテナンスモード切替リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class MaintenanceToggleRequest {

    @NotNull
    private final Boolean maintenance;
}
