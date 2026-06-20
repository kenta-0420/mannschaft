package com.mannschaft.app.todo.entity;

import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * TODO カスタムステータスラベルエンティティ（F02.3.1）。
 *
 * <p>ユーザー/チーム/組織が独自定義できるステータスラベル。
 * SYSTEM 既定ラベル（is_system_default=true）は名称・色・並び順・バケット・削除いずれも不変。</p>
 */
@Entity
@Table(name = "todo_status_labels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TodoStatusLabelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private TodoStatusLabelScope scopeType;

    /** SYSTEM スコープのときは NULL。それ以外は対応する scope の ID。 */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TodoStatusBucket bucket;

    /** #RRGGBB 形式の表示色（任意）。 */
    @Column(length = 7)
    private String color;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_system_default", nullable = false)
    @Builder.Default
    private Boolean isSystemDefault = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
        if (this.isSystemDefault == null) {
            this.isSystemDefault = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * SYSTEM 既定ラベルかを判定する。
     */
    public boolean isSystemDefault() {
        return Boolean.TRUE.equals(this.isSystemDefault);
    }

    /**
     * 論理削除を行う。SYSTEM 既定ラベルは削除不可。
     */
    public void softDelete() {
        assertMutable();
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * ラベル名を変更する。SYSTEM 既定ラベルは変更不可。
     */
    public void rename(String name) {
        assertMutable();
        this.name = name;
    }

    /**
     * 表示色を変更する。SYSTEM 既定ラベルは変更不可。
     */
    public void recolor(String color) {
        assertMutable();
        this.color = color;
    }

    /**
     * 並び順を変更する。SYSTEM 既定ラベルは変更不可。
     */
    public void reorder(Integer sortOrder) {
        assertMutable();
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }

    /**
     * バケットを変更する。SYSTEM 既定ラベルは変更不可。
     */
    public void changeBucket(TodoStatusBucket bucket) {
        assertMutable();
        this.bucket = bucket;
    }

    private void assertMutable() {
        if (isSystemDefault()) {
            throw new IllegalStateException("SYSTEM 既定ラベルは変更・削除できません: id=" + this.id);
        }
    }
}
