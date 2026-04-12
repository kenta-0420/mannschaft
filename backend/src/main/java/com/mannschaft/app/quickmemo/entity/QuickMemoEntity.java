package com.mannschaft.app.quickmemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ポイっとメモエンティティ。
 * 題名のみで保存可能。3段階リマインダー・論理削除・TODO昇格に対応。
 */
@Entity
@Table(name = "quick_memos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QuickMemoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** UNSORTED / ARCHIVED / CONVERTED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "UNSORTED";

    @Column(name = "reminder_uses_default", nullable = false)
    @Builder.Default
    private Boolean reminderUsesDefault = true;

    @Column(name = "reminder_1_scheduled_at")
    private LocalDateTime reminder1ScheduledAt;

    @Column(name = "reminder_1_sent_at")
    private LocalDateTime reminder1SentAt;

    @Column(name = "reminder_2_scheduled_at")
    private LocalDateTime reminder2ScheduledAt;

    @Column(name = "reminder_2_sent_at")
    private LocalDateTime reminder2SentAt;

    @Column(name = "reminder_3_scheduled_at")
    private LocalDateTime reminder3ScheduledAt;

    @Column(name = "reminder_3_sent_at")
    private LocalDateTime reminder3SentAt;

    /** TODO 昇格先の todos.id（非FK） */
    @Column(name = "converted_to_todo_id")
    private Long convertedToTodoId;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "user_timezone_at_creation", nullable = false, length = 50)
    @Builder.Default
    private String userTimezoneAtCreation = "Asia/Tokyo";

    /** 論理削除。90日後に物理削除バッチが実行される。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = "UNSORTED";
        }
        if (this.reminderUsesDefault == null) {
            this.reminderUsesDefault = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void archive() {
        this.status = "ARCHIVED";
    }

    public void restore() {
        this.status = "UNSORTED";
    }

    public void convertToTodo(Long todoId) {
        this.status = "CONVERTED";
        this.convertedToTodoId = todoId;
        this.convertedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
