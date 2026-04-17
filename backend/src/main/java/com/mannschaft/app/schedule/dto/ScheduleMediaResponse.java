package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * スケジュールメディアレスポンス DTO。
 * GET /media 一覧の items 要素、PATCH 更新後レスポンスに使用する。
 */
@Getter
@Builder
public class ScheduleMediaResponse {

    /** メディア ID */
    @JsonProperty("id")
    private Long id;

    /** メディア種別（IMAGE / VIDEO） */
    @JsonProperty("mediaType")
    private String mediaType;

    /** R2 配信 URL */
    @JsonProperty("url")
    private String url;

    /** 動画サムネイル URL（IMAGE / 未生成時は null） */
    @JsonProperty("thumbnailUrl")
    private String thumbnailUrl;

    /** ファイル名（元のファイル名） */
    @JsonProperty("fileName")
    private String fileName;

    /** ファイルサイズ（バイト） */
    @JsonProperty("fileSize")
    private Long fileSize;

    /** キャプション・説明文 */
    @JsonProperty("caption")
    private String caption;

    /** 撮影日時 */
    @JsonProperty("takenAt")
    private LocalDateTime takenAt;

    /** カバー写真フラグ */
    @JsonProperty("isCover")
    private Boolean isCover;

    /** 経費証憑フラグ */
    @JsonProperty("isExpenseReceipt")
    private Boolean isExpenseReceipt;

    /** 後処理ステータス（PENDING / PROCESSING / READY / FAILED） */
    @JsonProperty("processingStatus")
    private String processingStatus;

    /** アップロードしたユーザー ID */
    @JsonProperty("uploaderId")
    private Long uploaderId;

    /** レコード作成日時 */
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
