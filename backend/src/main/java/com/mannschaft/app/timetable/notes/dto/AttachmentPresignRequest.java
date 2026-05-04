package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * F03.15 添付ファイル アップロード用 Pre-signed URL 発行リクエスト。
 */
public record AttachmentPresignRequest(
        @NotBlank @Size(max = 255) @JsonProperty("file_name") String fileName,
        @NotBlank @Size(max = 100) @JsonProperty("content_type") String contentType,
        @Positive @JsonProperty("size_bytes") Long sizeBytes
) {
}
