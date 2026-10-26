package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 返金リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RefundRequest {

    @NotBlank
    private final String refundType;

    private final Integer refundAmount;

    private final Integer adjustedRemaining;

    @Size(max = 500)
    private final String note;
}
