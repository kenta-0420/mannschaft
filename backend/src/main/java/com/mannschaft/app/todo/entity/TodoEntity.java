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
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
