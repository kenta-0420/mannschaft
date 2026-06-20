package com.mannschaft.app.circulation.entity;

import com.mannschaft.app.circulation.CirculationExportStatus;
import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.CirculationPriority;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.StampDisplayStyle;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回覧文書エンティティ。回覧板の文書情報を管理する。
 */
@Entity
@Table(name = "circulation_documents")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CirculationDocumentEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CirculationMode circulationMode = CirculationMode.SIMULTANEOUS;

    @Column(nullable = false)
    @Builder.Default
    private Integer sequentialCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CirculationStatus status = CirculationStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private CirculationPriority priority = CirculationPriority.NORMAL;

    private LocalDate dueDate;

    @Column(nullable = false)
    @Builder.Default
    private Boolean reminderEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Short reminderIntervalHours = 24;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StampDisplayStyle stampDisplayStyle = StampDisplayStyle.STANDARD;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalRecipientCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer stampedCount = 0;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer attachmentCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer commentCount = 0;

    private LocalDateTime deletedAt;

    // ─────────────────────────────────────────────
    // F05.2 Phase 11 第四陣 4-C: 押印済み証跡 PDF エクスポート
    // ─────────────────────────────────────────────

    /** エクスポート生成状態（NOT_GENERATED / PENDING / COMPLETED / FAILED）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CirculationExportStatus exportStatus = CirculationExportStatus.NOT_GENERATED;

    /** R2 オブジェクトキー（生成完了時にセット）。 */
    @Column(length = 500)
    private String exportFileKey;

    /** エクスポート生成リクエスト受付時刻。 */
    private LocalDateTime exportRequestedAt;

    /** エクスポート生成完了時刻。 */
    private LocalDateTime exportCompletedAt;

    /** 生成失敗時のエラー要約。 */
    @Column(length = 1000)
    private String exportErrorMessage;

    /**
     * エクスポート生成リクエストを受け付けた直後の状態に遷移させる。
     */
    public void markExportPending() {
        this.exportStatus = CirculationExportStatus.PENDING;
        this.exportRequestedAt = LocalDateTime.now();
        this.exportErrorMessage = null;
    }

    /**
     * エクスポート生成完了状態に遷移させる。
     *
     * @param fileKey 生成済 PDF の R2 オブジェクトキー
     */
    public void markExportCompleted(String fileKey) {
        this.exportStatus = CirculationExportStatus.COMPLETED;
        this.exportFileKey = fileKey;
        this.exportCompletedAt = LocalDateTime.now();
        this.exportErrorMessage = null;
    }

    /**
     * エクスポート生成失敗状態に遷移させる。
     *
     * @param errorMessage エラー要約（最大 1000 文字、それ以上は切詰め）
     */
    public void markExportFailed(String errorMessage) {
        this.exportStatus = CirculationExportStatus.FAILED;
        if (errorMessage != null && errorMessage.length() > 1000) {
            this.exportErrorMessage = errorMessage.substring(0, 1000);
        } else {
            this.exportErrorMessage = errorMessage;
        }
    }

    /**
     * 文書を公開（ACTIVE）にする。
     */
    public void activate() {
        this.status = CirculationStatus.ACTIVE;
    }

    /**
     * 順次回覧の受信者数を設定する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void updateSequentialCount(int count) {
        this.sequentialCount = count;
    }

    /**
     * 文書を完了する。
     */
    public void complete() {
        this.status = CirculationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 文書をキャンセルする。
     */
    public void cancel() {
        this.status = CirculationStatus.CANCELLED;
    }

    /**
     * 受信者数を更新する。
     *
     * @param count 受信者数
     */
    public void updateRecipientCount(int count) {
        this.totalRecipientCount = count;
    }

    /**
     * 押印数をインクリメントする。
     */
    public void incrementStampedCount() {
        this.stampedCount++;
    }

    /**
     * 押印数をデクリメントする（押印訂正時に使用）。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 受信者が押印を訂正した際に、
     * 押印済み件数をデクリメントする。アンダーフロー防止のため、
     * 0 以下にはならない。</p>
     */
    public void decrementStampedCount() {
        if (this.stampedCount > 0) {
            this.stampedCount--;
        }
    }

    /**
     * 添付ファイル数をインクリメントする。
     */
    public void incrementAttachmentCount() {
        this.attachmentCount++;
    }

    /**
     * 添付ファイル数をデクリメントする。
     */
    public void decrementAttachmentCount() {
        if (this.attachmentCount > 0) {
            this.attachmentCount--;
        }
    }

    /**
     * コメント数をインクリメントする。
     */
    public void incrementCommentCount() {
        this.commentCount++;
    }

    /**
     * コメント数をデクリメントする。
     */
    public void decrementCommentCount() {
        if (this.commentCount > 0) {
            this.commentCount--;
        }
    }

    /**
     * タイトルと本文を更新する。
     *
     * @param title 新しいタイトル
     * @param body  新しい本文
     */
    public void updateContent(String title, String body) {
        this.title = title;
        this.body = body;
    }

    /**
     * 設定を更新する。
     *
     * @param priority            優先度
     * @param dueDate             期限日
     * @param reminderEnabled     リマインダー有効
     * @param reminderIntervalHours リマインダー間隔（時間）
     * @param stampDisplayStyle    押印表示スタイル
     */
    public void updateSettings(CirculationPriority priority, LocalDate dueDate,
                               Boolean reminderEnabled, Short reminderIntervalHours,
                               StampDisplayStyle stampDisplayStyle) {
        this.priority = priority;
        this.dueDate = dueDate;
        this.reminderEnabled = reminderEnabled;
        this.reminderIntervalHours = reminderIntervalHours;
        this.stampDisplayStyle = stampDisplayStyle;
    }

    /**
     * 全受信者が押印済みかどうかを判定する。
     *
     * @return 全員押印済みの場合 true
     */
    public boolean isAllStamped() {
        return this.totalRecipientCount > 0 && this.stampedCount >= this.totalRecipientCount;
    }

    /**
     * 編集可能かどうかを判定する（DRAFT のみ）。
     *
     * @return DRAFT ステータスの場合 true
     */
    public boolean isEditable() {
        return this.status == CirculationStatus.DRAFT;
    }

    /**
     * アクティブかどうかを判定する。
     *
     * @return ACTIVE ステータスの場合 true
     */
    public boolean isActive() {
        return this.status == CirculationStatus.ACTIVE;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
