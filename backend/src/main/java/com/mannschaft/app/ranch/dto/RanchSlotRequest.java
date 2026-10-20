package com.mannschaft.app.ranch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 配置先slotの版と本人所有inventory ID。 */
public record RanchSlotRequest(@NotNull UUID inventoryId, @NotBlank String version) {
    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
