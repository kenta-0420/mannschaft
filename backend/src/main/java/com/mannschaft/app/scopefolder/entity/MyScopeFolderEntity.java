package com.mannschaft.app.scopefolder.entity;

import com.mannschaft.app.scopefolder.entity.enums.ScopeType;

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
 * マイスコープフォルダエンティティ。
 * ユーザーがチームまたは組織を分類するために自由に作成するフォルダ。
 * 1ユーザー1スコープタイプあたり最大20フォルダまで。
 */
@Entity
@Table(name = "my_scope_folders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MyScopeFolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 7)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /**
     * 未分類フォルダフラグ。user_id × scope_type ごとに最大 1 行のみ TRUE。
     * 未分類フォルダは削除不可・改名不可・末尾固定（Service 層でバリデーション）。
     * 設計書 F15.3 §4.3
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;

    /**
     * PrimeIcons のアイコン名（例: pi-briefcase）。NULL 許容。
     * 設計書 F15.3 §4.3 / §9.8 で正規表現バリデーション。
     */
    @Column(name = "icon", length = 40)
    private String icon;

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
        if (this.isDefault == null) {
            this.isDefault = Boolean.FALSE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * フォルダの名前・色を更新する。
     */
    public void update(String name, String color) {
        this.name = name;
        this.color = color;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * フォルダの名前・色・アイコンを更新する。
     */
    public void update(String name, String color, String icon) {
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 「未分類」フォルダかどうかを返す（is_default = TRUE）。
     */
    public boolean isDefaultFolder() {
        return Boolean.TRUE.equals(this.isDefault);
    }

    /**
     * フォルダを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 並び順を更新する。
     */
    public void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
        this.updatedAt = LocalDateTime.now();
    }
}
