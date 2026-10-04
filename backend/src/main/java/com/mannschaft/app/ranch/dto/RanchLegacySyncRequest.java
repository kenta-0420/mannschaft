package com.mannschaft.app.ranch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

/** 旧取得行の有界走査位置。BIGINTは他APIと同じdecimal string。 */
public record RanchLegacySyncRequest(String afterAwardId) {
    public RanchLegacySyncRequest {
        if (afterAwardId == null || !afterAwardId.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("afterAwardIdが不正です");
        }
        try {
            Long.parseLong(afterAwardId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("afterAwardIdが不正です", exception);
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }

    public long afterId() { return Long.parseLong(afterAwardId); }
}
