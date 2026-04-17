package com.mannschaft.app.schedule.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * スケジュールメディアアップロード管理エンティティ。
 * 画像・動画の両方を扱う。
 * テーブル: schedule_media_uploads
 */
@Entity
@Table(name = "schedule_media_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleMediaUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スケジュール ID（FK → schedules。SET NULL on delete）*/
    private Long scheduleId;

    /** アップロードしたユーザー ID（FK → users。SET NULL on delete）*/
    private Long uploaderId;

    /** メディア種別（IMAGE / VIDEO）*/
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String mediaType = "IMAGE";

    /** R2 オブジェクトキー */
    @Column(name = "r2_key", nullable = false, length = 500)
    private String r2Key;

    /** 動画サムネイル R2 キー（VIDEO のみ。IMAGE / 未生成時は NULL）*/
    @Column(name = "thumbnail_r2_key", length = 500)
    private String thumbnailR2Key;

    /** 元のファイル名（表示名）*/
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** ファイルサイズ（バイト）*/
    @Column(nullable = false)
    private Long fileSize;

    /** MIME タイプ */
    @Column(nullable = false, length = 50)
    private String contentType;

    /** 動画再生時間（秒。VIDEO のみ）*/
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** キャプション・説明文（最大 500 文字）*/
    @Column(length = 500)
    private String caption;

    /** 撮影日時（EXIF から取得または手動入力。任意）*/
    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    /** カバー写真フラグ（schedule ごとに1件のみ TRUE 許容）*/
    @Column(name = "is_cover", nullable = false)
    @Builder.Default
    private Boolean isCover = false;

    /** 経費証憑フラグ（F13.x 経費精算機能連携用）*/
    @Column(name = "is_expense_receipt", nullable = false)
    @Builder.Default
    private Boolean isExpenseReceipt = false;

    /**
     * 後処理ステータス。
     * IMAGE: 常に READY。VIDEO: PENDING → PROCESSING → READY/FAILED。
     */
    @Column(name = "processing_status", nullable = false, length = 20)
    @Builder.Default
    private String processingStatus = "READY";

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * キャプションを更新する。
     */
    public void updateCaption(String caption) {
        this.caption = caption;
    }

    /**
     * 撮影日時を更新する。
     */
    public void updateTakenAt(LocalDateTime takenAt) {
        this.takenAt = takenAt;
    }

    /**
     * カバー写真フラグを更新する。
     */
    public void updateIsCover(boolean isCover) {
        this.isCover = isCover;
    }

    /**
     * 経費証憑フラグを更新する。
     */
    public void updateIsExpenseReceipt(boolean isExpenseReceipt) {
        this.isExpenseReceipt = isExpenseReceipt;
    }

    /**
     * 動画サムネイルのキーを設定する（Workers による非同期生成後）。
     */
    public void updateThumbnail(String thumbnailR2Key) {
        this.thumbnailR2Key = thumbnailR2Key;
    }

    /**
     * 処理ステータスを更新する（動画処理完了時等）。
     */
    public void updateProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }
}
