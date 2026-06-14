package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 局面写真の短命ダウンロード URL レスポンス（生 fileKey は返さない・03 §C.7a）。
 */
@Schema(name = "MatchRecordAttachmentDownloadResponse")
@Getter
@Builder
public class MatchAttachmentDownloadResponse {

    /** 短命 presigned GET URL。 */
    private final String downloadUrl;

    /** 有効期限（秒）。 */
    private final long expiresInSeconds;
}
