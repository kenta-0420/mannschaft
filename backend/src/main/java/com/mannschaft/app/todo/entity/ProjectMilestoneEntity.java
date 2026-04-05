package com.mannschaft.app.todo.entity;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイルストーン（中間目標）エンティティ。プロジェクトをフェーズ分割する。
 */
@Entity
@Table(name = "project_milestones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProjectMilestoneEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String title;

    private LocalDate dueDate;

    @Column(nullable = false)
    private Short sortOrder;

    @Column(nullable = false)
    private Boolean isCompleted;

    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sortOrder == null) {
            this.sortOrder = (short) 0;
        }
        if (this.isCompleted == null) {
            this.isCompleted = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * マイルストーンを完了にする。
     */
    public void complete() {
        this.isCompleted = true;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * マイルストーンを未完了に戻す。
     */
    public void reopen() {
        this.isCompleted = false;
        this.completedAt = null;
    }
}
