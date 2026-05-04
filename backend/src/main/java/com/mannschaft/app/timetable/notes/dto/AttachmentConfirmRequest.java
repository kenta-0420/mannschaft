package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F03.15 添付ファイル confirm リクエスト（presign で得たキーを送信）。
 */
public record AttachmentConfirmRequest(
        @NotBlank @Size(max = 500) @JsonProperty("r2_object_key") String r2ObjectKey
) {
}
