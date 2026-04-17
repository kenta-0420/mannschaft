package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * スケジュールメディアアップロード URL 発行レスポンス DTO。
 * F03.12 カレンダー予定メディア管理。
 * IMAGE の場合は uploadUrl / expiresIn を返す。
 * VIDEO の場合は uploadId / partSize を返す。
 */
@Getter
@Builder
public class ScheduleMediaUploadUrlResponse {

    /** 作成されたメディアID */
    private Long mediaId;

    /** メディア種別（"IMAGE" または "VIDEO"） */
    private String mediaType;

    /** R2 ストレージキー */
    private String r2Key;

    // --- IMAGE のみ ---

    /** Presigned PUT URL（IMAGE のみ） */
    private String uploadUrl;

    /** URL 有効期限（秒）（IMAGE のみ） */
    private Integer expiresIn;

    // --- VIDEO のみ ---

    /** Multipart Upload ID（VIDEO のみ） */
    private String uploadId;

    /** 推奨パートサイズ（バイト単位）（VIDEO のみ） */
    private Long partSize;
}
