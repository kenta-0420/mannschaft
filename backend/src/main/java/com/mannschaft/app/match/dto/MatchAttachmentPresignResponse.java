package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 局面写真 presign レスポンス（uploadUrl / fileKey / 有効期限・01 §B.7 / 03 §C.7a）。
 */
@Schema(name = "MatchRecordAttachmentPresignResponse")
@Getter
@Builder
public class MatchAttachmentPresignResponse {

    /** presigned PUT URL。 */
    private final String uploadUrl;

    /** server 採番 fileKey（確定時に送り返す）。 */
    private final String fileKey;

    /** 有効期限（秒）。 */
    private final long expiresInSeconds;
}
