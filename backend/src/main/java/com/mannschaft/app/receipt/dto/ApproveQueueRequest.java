package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * キューアイテム承認リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApproveQueueRequest {

    @Size(max = 500)
    private final String description;

    private final BigDecimal amount;

    private final Boolean sealStamp;
}
