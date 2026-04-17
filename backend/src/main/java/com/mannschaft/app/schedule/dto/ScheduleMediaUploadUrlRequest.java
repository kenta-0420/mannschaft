package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * スケジュールメディアアップロードURL発行リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class ScheduleMediaUploadUrlRequest {

    /** メディア種別（IMAGE または VIDEO） */
    @NotBlank
    @JsonProperty("mediaType")
    private String mediaType;

    /** MIME タイプ */
    @NotBlank
    @JsonProperty("contentType")
    private String contentType;

    /** ファイルサイズ（バイト） */
    @Positive
    @JsonProperty("fileSize")
    private long fileSize;

    /** ファイル名（表示名） */
    @NotBlank
    @JsonProperty("fileName")
    private String fileName;
}
