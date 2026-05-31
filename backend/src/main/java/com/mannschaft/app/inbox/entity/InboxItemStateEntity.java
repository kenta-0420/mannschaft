package com.mannschaft.app.inbox.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.inbox.InboxSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F04.11 統合通知インボックス：per-user の triage 状態オーバーレイ。
 *
 * <p>通知 1 件（{@code (source_type, source_id)} で論理参照）に対する、ユーザーごとの
 * スヌーズ・アーカイブ状態を保持する。<b>遅延生成</b>：デフォルト（未スヌーズ・未アーカイブ）は
 * 行を作らず、操作時に upsert、両方解除されたら物理削除する（ADHD 要件「整理不要なものは持たない」）。</p>
 *
 * <p>手本は {@code UserFavoriteEntity}（ポリモーフィック per-user 表）。
 * {@code user_id} / {@code source_id} への FK 制約は意図的に設けない（CLAUDE.md 原則1）。
 * 論理削除なし（物理削除）。設計書: 01_data_model.md §2.1。</p>
 */
@Entity
@Table(name = "inbox_item_states")
@Getter
@Setter
@NoArgsConstructor
public class InboxItemStateEntity extends UuidV7Entity {

    /** 対象ユーザーID（FK 張らない） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 通知ソース種別（NOTIFICATION/ANNOUNCEMENT/MENTION/CONFIRMABLE/TODO_DUE） */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private InboxSourceType sourceType;

    /** 各ソーステーブルのPK（FK なし・論理参照） */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    /** スヌーズ解除予定時刻。NULL=非スヌーズ。now 超過で受信箱へ自動復帰 */
    @Column(name = "snoozed_until")
    private LocalDateTime snoozedUntil;

    /** アーカイブ退避時刻。NULL=受信箱、非NULL=保管庫 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

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
}
