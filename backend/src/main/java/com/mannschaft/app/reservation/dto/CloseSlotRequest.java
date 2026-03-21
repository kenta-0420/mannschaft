package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スロットクローズリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CloseSlotRequest {

    @Size(max = 20)
    private final String reason;
}
