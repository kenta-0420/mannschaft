package com.mannschaft.app.circulation.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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

    /**
     * 文書を公開（ACTIVE）にする。
     */
    public void activate() {
        this.status = CirculationStatus.ACTIVE;
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
