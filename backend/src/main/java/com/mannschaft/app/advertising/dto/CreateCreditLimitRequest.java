package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCreditLimitRequest(
    @NotNull @DecimalMin(value = "1", inclusive = true) BigDecimal requestedLimit,
    @NotBlank @Size(max = 500) String reason
) {}
