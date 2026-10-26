package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフトポジション作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePositionRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    private final Integer displayOrder;
}
