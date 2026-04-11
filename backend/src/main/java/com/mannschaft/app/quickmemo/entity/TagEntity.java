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
 * 汎用タグマスタ。ポイっとメモ / TODO で共通利用。
 * PERSONAL / TEAM / ORGANIZATION スコープで管理する。
 */
@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PERSONAL / TEAM / ORGANIZATION */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /** PERSONAL=user_id / TEAM=team_id / ORGANIZATION=organization_id */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    /** HEX カラーコード (#RRGGBB) */
    @Column(name = "color", length = 7)
    private String color;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    /** FK -> users.id (ON DELETE RESTRICT) */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.usageCount == null) {
            this.usageCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changeColor(String newColor) {
        this.color = newColor;
    }
}
