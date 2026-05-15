package com.mannschaft.app.favorite.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.favorite.FavoriteEntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ユーザー横断お気に入りエンティティ。
 *
 * <p>複数のドメイン（チーム・組織・ナレッジベースページ等）のエンティティを
 * 統一的にお気に入り管理するテーブル。ダッシュボードのショートカット一覧として活用される。</p>
 *
 * <p>user_id への FK 制約は意図的に設けていない（CLAUDE.md 原則1: クロスドメイン FK 禁止）。
 * 退会ユーザーのお気に入りはバッチで物理削除する運用とする。</p>
 */
@Entity
@Table(name = "user_favorites")
@Getter
@Setter
@NoArgsConstructor
public class UserFavoriteEntity extends UuidV7Entity {

    /** お気に入り登録者のユーザーID（FK 張らない） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** エンティティ種別（TEAM / ORGANIZATION / KB_PAGE / BLOG_AUTHOR / VILLAGE） */
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    private FavoriteEntityType entityType;

    /**
     * エンティティID（文字列型で統一）。
     * BIGINT 型 ID のエンティティは十進数文字列（例: "123"）、
     * UUID 型 ID のエンティティは36文字ハイフン付き文字列（例: "018f-..."）で格納する。
     */
    @Column(name = "entity_id", nullable = false, length = 36)
    private String entityId;

    /** 表示順（低い値が先頭） */
    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    /** 登録日時（自動設定、更新不可） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 永続化直前に登録日時を自動設定する。
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
