package com.mannschaft.app.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * コンテンツゲート一括設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ContentPaymentGateRequest {

    @NotNull
    private final String contentType;

    @NotNull
    private final Long contentId;

    @NotNull
    @Valid
    private final List<GateEntry> gates;

    /**
     * 個別ゲート設定。
     */
    @Getter
    @RequiredArgsConstructor
    public static class GateEntry {

        @NotNull
        private final Long paymentItemId;

        private final Boolean isTitleHidden;
    }
}
