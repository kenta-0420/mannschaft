package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record MarkInvoicePaidRequest(
    @NotNull LocalDateTime paidAt,
    @Size(max = 500) String note
) {}
