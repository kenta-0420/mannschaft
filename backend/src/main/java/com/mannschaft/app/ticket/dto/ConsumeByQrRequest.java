package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * QR スキャン消化リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumeByQrRequest {

    @NotBlank
    private final String qrPayload;

    @Size(max = 500)
    private final String note;
}
