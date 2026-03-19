package com.mannschaft.app.todo.entity;

import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.ProjectVisibility;
import com.mannschaft.app.todo.TodoScopeType;
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

/**
 * プロジェクト（目標）エンティティ。文化祭準備、結婚式、子供の夏休み宿題等の目標を定義する。
 */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 10)
    private String emoji;

    @Column(length = 7)
    private String color;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal progressRate;

    @Column(nullable = false)
    private Short totalTodos;

    @Column(nullable = false)
    private Short completedTodos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectVisibility visibility;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime completedAt;

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
            this.status = ProjectStatus.ACTIVE;
        }
        if (this.progressRate == null) {
            this.progressRate = BigDecimal.ZERO;
        }
        if (this.totalTodos == null) {
            this.totalTodos = (short) 0;
        }
        if (this.completedTodos == null) {
            this.completedTodos = (short) 0;
        }
        if (this.visibility == null) {
            this.visibility = ProjectVisibility.MEMBERS_ONLY;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * プロジェクトを手動完了にする。
     */
    public void complete() {
        this.status = ProjectStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 完了プロジェクトを再開する。
     */
    public void reopen() {
        this.status = ProjectStatus.ACTIVE;
        this.completedAt = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
