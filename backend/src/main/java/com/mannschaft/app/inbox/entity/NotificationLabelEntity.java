package com.mannschaft.app.inbox.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F04.11 統合通知インボックス：ユーザー定義の軽量ラベル（マスタ）。
 *
 * <p>per-user の「要件別」ラベル名前空間。他人と共有しない。手本は {@code ActionMemoTagEntity}
 * （ただし基底を {@code BaseEntity} → {@code UuidV7Entity} に差し替え＝新規テーブル UUIDv7 規約・原則6）。
 * 色は F15.2/F15.3 のフォルダ規約（{@code #RRGGBB}）に合わせる。上限 20 件/ユーザー（サービス層検証）。</p>
 *
 * <p>論理削除あり（{@code deleted_at} + {@code @SQLRestriction}）。誤削除リカバリ・履歴のため。
 * 設計書: 01_data_model.md §2.2。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "inbox")} により
 * インボックス3表として {@code inbox.json} に束ねてエクスポートされる
 * （論理削除済みは {@code @SQLRestriction} により自動除外）。設計書: 04_security_operations.md。</p>
 */
@PersonalData(category = "inbox")
@Entity
@Table(name = "notification_labels")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class NotificationLabelEntity extends UuidV7Entity {

    /** 所有ユーザーID（FK 張らない） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** ラベル名。{@code (user_id, name, deleted_at)} で一意（現役同名重複のみ禁止） */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 表示色 #RRGGBB（任意） */
    @Column(name = "color", length = 7)
    private String color;

    /** PrimeIcons 名（任意。例 pi-tag） */
    @Column(name = "icon", length = 40)
    private String icon;

    /** 表示順（昇順） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 論理削除日時 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 作成日時（自動設定、更新不可） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新日時（自動設定） */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ラベルを論理削除する。
     * 中間テーブル（inbox_label_links）は残す（一覧時に現役ラベルのみ join される）。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
