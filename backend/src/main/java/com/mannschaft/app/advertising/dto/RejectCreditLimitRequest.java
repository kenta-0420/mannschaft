package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Size;

public record RejectCreditLimitRequest(
    @Size(max = 500) String reviewNote
) {}
