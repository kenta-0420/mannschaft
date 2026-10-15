package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 領収書無効化リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class VoidReceiptRequest {

    @NotBlank
    @Size(max = 500)
    private final String reason;
}
