package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動発行（現地決済）リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class IssueTicketBookRequest {

    @NotNull
    private final Long productId;

    @NotNull
    private final Long userId;

    @NotBlank
    private final String paymentMethod;

    @NotNull
    @Min(0)
    private final Integer amount;

    @Size(max = 500)
    private final String note;
}
