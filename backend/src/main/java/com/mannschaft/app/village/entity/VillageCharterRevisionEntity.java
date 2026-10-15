package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村憲章の改定履歴（軽量・append-only）エンティティ（F17.3・設計書 §13.1.4）。
 *
 * <p>「改正を確定」時に 1 行追記する軽量履歴。{@code revisedAt}（日付）＋{@code note}（任意メモ）のみで、
 * そのときの条文全文スナップショットは持たない（版管理はスコープ外・§8.3）。append-only ゆえ
 * 論理削除列・{@code version}・{@code updatedAt} は持たない。{@code charterId} は同一ドメイン内
 * アグリゲート（FK CASCADE）。</p>
 */
@Entity
@Table(name = "village_charter_revisions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCharterRevisionEntity extends UuidV7Entity {

    /** → village_charters.id（同一ドメイン・FK CASCADE）。 */
    @Column(name = "charter_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID charterId;

    /** 改定日時（「改正を確定」時刻・§8.2）。 */
    @Column(name = "revised_at", nullable = false)
    private LocalDateTime revisedAt;

    /** 任意メモ（条文スナップショット無し・§8.3）。 */
    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
