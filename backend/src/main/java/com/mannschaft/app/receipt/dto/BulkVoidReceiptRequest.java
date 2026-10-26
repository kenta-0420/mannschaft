package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 領収書一括無効化リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkVoidReceiptRequest {

    @NotEmpty
    @Size(max = 50)
    private final List<Long> receiptIds;

    @NotBlank
    @Size(max = 500)
    private final String reason;
}
