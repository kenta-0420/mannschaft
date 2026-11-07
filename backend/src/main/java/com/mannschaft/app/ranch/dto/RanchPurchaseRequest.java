package com.mannschaft.app.ranch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/** クライアント価格やuser IDを受け取らず、SKU/価格版/owner版だけを受ける。 */
public record RanchPurchaseRequest(@NotBlank String skuKey,
                                   @NotBlank String priceVersion,
                                   @NotBlank String version) {
    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
