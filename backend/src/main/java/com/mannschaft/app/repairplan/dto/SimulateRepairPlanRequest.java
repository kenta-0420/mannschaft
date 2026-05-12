package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SimulateRepairPlanRequest(

        @NotNull @DecimalMin("0") BigDecimal monthlyFee,
        @NotNull @Min(0) Integer dwellingUnits,

        @NotNull @DecimalMin("-0.10") @DecimalMax("0.20")
        BigDecimal reserveInflationRate,

        @NotNull @DecimalMin("-0.10") @DecimalMax("0.20")
        BigDecimal cpiInflationRate,

        @Min(0) @Max(30) int deferralYears,

        @NotNull @DecimalMin("0") BigDecimal loanPrincipal,
        @NotNull @DecimalMin("0") BigDecimal loanInterestRate,
        @Min(0) @Max(50) int loanTermYears,

        @NotNull @DecimalMin("0") BigDecimal fixedManagementCostYearly,

        @NotNull @Min(1) @Max(50) Integer scenarioHorizonYears,

        @NotNull LocalDateTime baselineAt
) {}
