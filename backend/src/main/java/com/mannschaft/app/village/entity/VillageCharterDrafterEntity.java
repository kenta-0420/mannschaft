package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * 村憲章の策定者（複数可・表示順あり）エンティティ（F17.3・設計書 §13.1.3）。
 *
 * <p>制定/追加時の<b>村ニックネームを {@code nicknameSnapshot} に焼き付け</b>、退村・退会後も残す
 * （§2決定5・§5.2）。退会時は {@code userId} のみ NULL 化して個人リンクを切断し、仮名文字列
 * （{@code nicknameSnapshot}）は残置する（原則4・実名は元々保存しない＝§10 G4）。</p>
 *
 * <p>{@code userId} は別ドメイン(users) → FK非付与（原則1・NULL 許容）。物理削除運用（付け外し操作）
 * のため論理削除列・{@code version} は持たない（§13.2）。{@code charterId} は同一ドメイン内
 * アグリゲート（FK CASCADE）。</p>
 */
@Entity
@Table(name = "village_charter_drafters")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageCharterDrafterEntity extends UuidV7Entity {

    /** → village_charters.id（同一ドメイン・FK CASCADE）。 */
    @Column(name = "charter_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID charterId;

    /** 策定者（FK非付与・退会時 NULL 化・原則1/4）。 */
    @Column(name = "user_id")
    private Long userId;

    /** 制定/追加時の村ニックネーム焼付（退会後も残置・§5.2）。 */
    @Column(name = "nickname_snapshot", nullable = false, length = 40)
    private String nicknameSnapshot;

    /** 表示順（0始まり・末尾追加）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
