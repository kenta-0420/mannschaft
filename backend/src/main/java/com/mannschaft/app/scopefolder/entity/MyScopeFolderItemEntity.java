package com.mannschaft.app.scopefolder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * マイスコープフォルダアイテムエンティティ。
 * フォルダに紐づくチームまたは組織のIDを保持する。
 * 1スコープ（チームID/組織ID）は1つのフォルダにのみ所属できる。
 */
@Entity
@Table(name = "my_scope_folder_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MyScopeFolderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folder_id", nullable = false)
    private Long folderId;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /**
     * 割当経路の監査区分。INVITE / MANUAL / MIGRATION / DEFAULT。
     * 設計書 F15.3 §4.3 / §6.5
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_via", nullable = false, length = 20)
    private AssignedVia assignedVia;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
        if (this.assignedVia == null) {
            this.assignedVia = AssignedVia.MANUAL;
        }
    }
}
