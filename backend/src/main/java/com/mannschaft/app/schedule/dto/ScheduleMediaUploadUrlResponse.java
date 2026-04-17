package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * スケジュールメディアアップロードURL発行レスポンス DTO。
 * IMAGE: uploadUrl / expiresIn が設定される。uploadId / partSize は null。
 * VIDEO / 100MB超: uploadId / partSize が設定される。uploadUrl / expiresIn は null。
 */
@Getter
@Builder
public class ScheduleMediaUploadUrlResponse {

    /** 作成されたメディアレコードの ID */
    @JsonProperty("mediaId")
    private Long mediaId;

    /** メディア種別（IMAGE / VIDEO） */
    @JsonProperty("mediaType")
    private String mediaType;

    /** R2 オブジェクトキー */
    @JsonProperty("r2Key")
    private String r2Key;

    /** Presigned PUT URL（IMAGE のみ。VIDEO の場合は null） */
    @JsonProperty("uploadUrl")
    private String uploadUrl;

    /** URL 有効期限（秒）（IMAGE のみ。VIDEO の場合は null） */
    @JsonProperty("expiresIn")
    private Integer expiresIn;

    /** Multipart Upload ID（VIDEO のみ。IMAGE の場合は null） */
    @JsonProperty("uploadId")
    private String uploadId;

    /** 推奨パートサイズ（バイト）（VIDEO のみ。IMAGE の場合は null） */
    @JsonProperty("partSize")
    private Long partSize;
}
