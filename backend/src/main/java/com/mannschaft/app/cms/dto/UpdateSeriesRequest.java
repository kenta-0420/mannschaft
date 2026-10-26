package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シリーズ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSeriesRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;
}
