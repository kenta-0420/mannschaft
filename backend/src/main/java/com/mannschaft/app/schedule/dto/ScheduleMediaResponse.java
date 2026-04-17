package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * スケジュールメディア単件レスポンス DTO。
 * F03.12 カレンダー予定メディア管理。
 */
@Getter
@Builder
public class ScheduleMediaResponse {

    /** メディアID */
    private Long id;

    /** メディア種別（"IMAGE" または "VIDEO"） */
    private String mediaType;

    /** R2 配信 URL */
    private String url;

    /** サムネイル URL */
    private String thumbnailUrl;

    /** ファイル名（表示名） */
    private String fileName;

    /** ファイルサイズ（バイト単位） */
    private Long fileSize;

    /** キャプション */
    private String caption;

    /** 撮影日時 */
    private LocalDateTime takenAt;

    /** カバー写真フラグ */
    private Boolean isCover;

    /** 経費領収書フラグ */
    private Boolean isExpenseReceipt;

    /** 処理ステータス（PENDING / PROCESSING / COMPLETED / FAILED） */
    private String processingStatus;

    /** アップロードしたユーザーID */
    private Long uploaderId;

    /** 作成日時 */
    private LocalDateTime createdAt;
}
