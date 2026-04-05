package com.mannschaft.app.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 手動支払い一括記録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkPaymentRequest {

    @NotNull
    @Size(min = 1, max = 50)
    @Valid
    private final List<CreateManualPaymentRequest> payments;
}
