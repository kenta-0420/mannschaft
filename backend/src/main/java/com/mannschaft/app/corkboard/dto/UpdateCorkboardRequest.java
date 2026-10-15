package com.mannschaft.app.corkboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コルクボード更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCorkboardRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 10)
    private final String backgroundStyle;

    @Size(max = 20)
    private final String editPolicy;

    private final Boolean isDefault;
}
