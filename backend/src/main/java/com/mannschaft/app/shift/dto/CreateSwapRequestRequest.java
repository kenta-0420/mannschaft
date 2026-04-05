package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフト交代リクエスト作成DTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSwapRequestRequest {

    @NotNull
    private final Long slotId;

    @Size(max = 500)
    private final String reason;
}
