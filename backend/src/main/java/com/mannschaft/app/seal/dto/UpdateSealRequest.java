package com.mannschaft.app.seal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 電子印鑑更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSealRequest {

    @NotBlank
    @Size(max = 20)
    private final String displayText;
}
