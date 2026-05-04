package com.mannschaft.app.timetable.notes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * F03.15 添付ファイル Pre-signed URL 発行レスポンス。
 *
 * <p>クライアントは {@code uploadUrl} に対して PUT した後、{@code r2ObjectKey} を
 * confirm に送信して登録を確定する。</p>
 */
public record AttachmentPresignResponse(
        @JsonProperty("upload_url") String uploadUrl,
        @JsonProperty("r2_object_key") String r2ObjectKey,
        @JsonProperty("expires_in") Long expiresIn
) {
}
