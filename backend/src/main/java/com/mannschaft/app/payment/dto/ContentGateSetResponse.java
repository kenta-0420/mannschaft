package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * コンテンツゲート一括設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ContentGateSetResponse {

    private final String contentType;
    private final Long contentId;
    private final List<GateItem> gates;

    /**
     * 設定されたゲート項目。
     */
    @Getter
    @RequiredArgsConstructor
    public static class GateItem {
        private final Long id;
        private final Long paymentItemId;
        private final String paymentItemName;
        private final Boolean isTitleHidden;
    }
}
