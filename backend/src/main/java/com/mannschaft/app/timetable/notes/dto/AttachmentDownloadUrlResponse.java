package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * F03.15 添付ファイル ダウンロード用 Pre-signed URL レスポンス。
 */
public record AttachmentDownloadUrlResponse(
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("expires_in") Long expiresIn
) {
}
