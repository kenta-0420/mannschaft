package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村憲章の条（1条1レコード）エンティティ（F17.3・設計書 §13.1.2）。
 *
 * <p>条番号（第一条…）は<b>保存しない</b>。表示採番は非削除条を {@code sortOrder} 昇順に並べた
 * {@code index+1} で導出する（§6.1）。{@code sortOrder} には UNIQUE を張らない（並び替え中間状態
 * で一時重複しうる＋論理削除行が同一空間に残るため・§6.2）。</p>
 *
 * <p>{@code version}（{@link Version}）は<b>層1 楽観ロック</b>で、本文/付則の in-place 更新
 * （{@code PUT}）でのみ検査する。再連番の bulk UPDATE は層1 を触らない（§6.3・§7）。
 * {@code charterId} は同一ドメイン内アグリゲート（FK CASCADE）、{@code villageId} は IDOR 照合用の
 * 冗長列（§AC-08）。論理削除（{@code deletedAt}）で原則3。</p>
 */
@Entity
@Table(name = "village_charter_articles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCharterArticleEntity extends UuidV7Entity {

    /** → village_charters.id（同一ドメイン・FK CASCADE）。 */
    @Column(name = "charter_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID charterId;

    /** 村スコープの冗長列（IDOR 照合用・§AC-08）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 0始まり連番（表示採番の元・UNIQUE張らない・§6.2）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 条文（必須）。 */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** 付則（任意）。 */
    @Column(name = "supplement", columnDefinition = "TEXT")
    private String supplement;

    /** 論理削除（原則3・削除後は再連番から除外）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** @Version（本文更新＝層1 楽観ロック・§7）。 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
