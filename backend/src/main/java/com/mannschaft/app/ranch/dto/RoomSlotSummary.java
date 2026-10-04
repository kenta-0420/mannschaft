package com.mannschaft.app.ranch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のRoomSlotSummary。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RoomSlotSummary(String slotKey, UUID inventoryId, String version,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Decoration decoration) {
    /** 既存の配置・取外しcommand ACKには表示投影を含めない。 */
    public RoomSlotSummary(String slotKey, UUID inventoryId, String version) {
        this(slotKey, inventoryId, version, null);
    }

    /** 運営承認済みcatalogの有限キーだけ。名称・自由URLを含めない。 */
    public record Decoration(String collectibleKey, String labelKey, String assetKey) { }
}
