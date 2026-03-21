package com.mannschaft.app.seal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 電子印鑑作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSealRequest {

    @NotNull
    private final String variant;

    @NotBlank
    @Size(max = 20)
    private final String displayText;
}
