package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 領収書一括発行リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateReceiptRequest {

    @NotEmpty
    @Size(max = 50)
    private final List<Long> memberPaymentIds;

    @Size(max = 500)
    private final String description;

    private final Boolean sealStamp;

    private final Long presetId;
}
