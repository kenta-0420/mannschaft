package com.mannschaft.app.shift.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * デフォルト勤務可能時間一括設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkAvailabilityDefaultRequest {

    @NotEmpty
    @Valid
    private final List<AvailabilityDefaultRequest> availabilities;
}
