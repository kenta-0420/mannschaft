package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のRanchHatchRequest。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RanchHatchRequest(@NotBlank String version, @NotNull String name, @NotNull Boolean nameConfirmed) {
    @JsonAnySetter
    public void rejectUnknownField(String field, JsonNode value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
