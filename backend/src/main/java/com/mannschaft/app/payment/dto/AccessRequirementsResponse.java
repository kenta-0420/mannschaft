package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アクセス要件設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AccessRequirementsResponse {

    private final Long teamId;
    private final Long organizationId;
    private final List<PaymentItemRef> requiredPaymentItems;

    /**
     * 支払い項目の参照情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PaymentItemRef {
        private final Long id;
        private final String name;
    }
}
