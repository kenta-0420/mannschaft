package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ステータス理由リクエストDTO（拒否・取り下げ共用）。
 */
@Getter
@RequiredArgsConstructor
public class StatusReasonRequest {

    @Size(max = 500)
    private final String statusReason;
}
