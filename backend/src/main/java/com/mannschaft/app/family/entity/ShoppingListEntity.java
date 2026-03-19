package com.mannschaft.app.family.entity;

import com.mannschaft.app.family.ShoppingListStatus;
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

import java.time.LocalDateTime;

/**
 * お買い物リストエンティティ。チーム共有のお買い物リスト（ヘッダー）を表す。
 */
@Entity
@Table(name = "shopping_lists")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShoppingListEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Boolean isTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShoppingListStatus status;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ShoppingListStatus.ACTIVE;
        }
        if (this.isTemplate == null) {
            this.isTemplate = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void rename(String name) {
        this.name = name;
    }

    public void archive() {
        this.status = ShoppingListStatus.ARCHIVED;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
