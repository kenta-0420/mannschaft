package com.mannschaft.app.todo.entity;

import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * TODOタスクエンティティ。チェックボックス付きのタスクリストで、期限・優先度・担当者を管理する。
 */
@Entity
@Table(name = "todos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TodoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long projectId;

    private Long milestoneId;

    /** 所属マイルストーンがロック中の場合 TRUE。ステータス変更 API の事前チェックに使用（F02.7） */
    @Column(name = "milestone_locked", nullable = false)
    @Builder.Default
    private Boolean milestoneLocked = false;

    /** 同一マイルストーン内での表示順。ドラッグ＆ドロップ並び替え用（F02.7） */
    @Column(nullable = false)
    @Builder.Default
    private Integer position = 0;

    private Long parentId;

    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TodoPriority priority;

    private LocalDate dueDate;

    private LocalTime dueTime;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "linked_schedule_id")
    private Long linkedScheduleId;

    /** シフト枠との連携ID。シフト公開時の Todo 自動作成で設定（F03.5 Phase 4-β）。 */
    @Column(name = "linked_shift_slot_id")
    private Long linkedShiftSlotId;

    @Column(name = "progress_rate", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal progressRate = BigDecimal.ZERO;

    @Column(name = "progress_manual", nullable = false)
    @Builder.Default
    private Boolean progressManual = false;

    private LocalDateTime completedAt;

    private Long completedBy;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = TodoStatus.OPEN;
        }
        if (this.priority == null) {
            this.priority = TodoPriority.MEDIUM;
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * TODOを完了にする。
     *
     * @param userId 完了操作したユーザーID
     */
    public void complete(Long userId) {
        this.status = TodoStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.completedBy = userId;
    }

    /**
     * TODOを未完了に戻す。
     */
    public void uncomplete() {
        this.completedAt = null;
        this.completedBy = null;
    }

    /**
     * ステータスを変更する。
     *
     * @param newStatus 新しいステータス
     * @param userId    操作ユーザーID
     */
    public void changeStatus(TodoStatus newStatus, Long userId) {
        if (newStatus == TodoStatus.COMPLETED) {
            complete(userId);
        } else {
            if (this.status == TodoStatus.COMPLETED) {
                uncomplete();
            }
            this.status = newStatus;
        }
    }

    /**
     * プロジェクト間移動時にマイルストーンをリセットする。
     *
     * @param newProjectId 新しいプロジェクトID（NULLで切り離し）
     */
    public void moveToProject(Long newProjectId) {
        this.projectId = newProjectId;
        this.milestoneId = null;
    }

    /**
     * 期限日を変更する。
     *
     * @param dueDate 新しい期限日（nullで期限なしに変更）
     */
    public void updateDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 開始日を変更する。
     *
     * @param startDate 新しい開始日（nullで開始日なしに変更）
     */
    public void updateStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    /**
     * スケジュール連携IDを設定する（双方向リンク用）。
     *
     * @param linkedScheduleId 連携スケジュールID（nullで連携解除）
     */
    public void setLinkedScheduleId(Long linkedScheduleId) {
        this.linkedScheduleId = linkedScheduleId;
    }

    /**
     * 進捗率を手動設定する。
     *
     * @param progressRate   進捗率（0.00〜100.00）
     * @param progressManual 手動設定フラグ
     */
    public void setProgressRate(java.math.BigDecimal progressRate, boolean progressManual) {
        this.progressRate = progressRate;
        this.progressManual = progressManual;
    }

    /**
     * マイルストーンロックを適用（F02.7）。
     */
    public void lockByMilestone() {
        this.milestoneLocked = true;
    }

    /**
     * マイルストーンロックを解除（F02.7）。
     */
    public void unlockByMilestone() {
        this.milestoneLocked = false;
    }

    /**
     * 表示順を設定（F02.7）。
     *
     * @param pos マイルストーン内での位置（0始まり）
     */
    public void setPosition(Integer pos) {
        this.position = pos;
    }
}
