package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アクセス要件設定リクエストDTO。空リストでロック解除。
 */
@Getter
@RequiredArgsConstructor
public class AccessRequirementsRequest {

    @NotNull
    private final List<Long> paymentItemIds;
}
