package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * スケジュールメディアアップロード URL 発行リクエスト DTO。
 * F03.12 カレンダー予定メディア管理。
 */
@Getter
@NoArgsConstructor
public class ScheduleMediaUploadUrlRequest {

    /** メディア種別（"IMAGE" または "VIDEO"） */
    @NotBlank
    private String mediaType;

    /** MIME タイプ（例: "image/jpeg", "video/mp4"） */
    @NotBlank
    private String contentType;

    /** ファイルサイズ（バイト単位） */
    @Positive
    private long fileSize;

    /** 元のファイル名（表示名として保存） */
    @NotBlank
    private String fileName;
}
