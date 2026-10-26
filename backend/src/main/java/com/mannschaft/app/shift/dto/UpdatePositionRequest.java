package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフトポジション更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePositionRequest {

    @Size(max = 50)
    private final String name;

    private final Integer displayOrder;

    private final Boolean isActive;
}
