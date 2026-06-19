package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ユーザーの村ニックネームエンティティ（F17.1 Phase 1）。
 *
 * <p>Phase 1 は 1 ユーザー = 1 ニックネーム（{@code villageId IS NULL} の行 1 件）。
 * Phase 2 で村ごと上書き行（{@code villageId} 付き）を追加可能にする想定。
 * ニックネームはプラットフォーム全体で一意（先着優先）。</p>
 *
 * <p>退会時は <b>物理削除</b>（個人特定情報の最たるもの）。</p>
 */
@Entity
@Table(name = "user_village_nicknames")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UserVillageNicknameEntity extends UuidV7Entity {

    /** ユーザーID（FK 張らない／原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * NULL = 全村共通（Phase 1 デフォルト）。
     * 特定 ID = その村専用の上書き（Phase 2 で導入予定）。
     * FK は張らない（NULL 許容＋原則1）。
     */
    @Column(name = "village_id", columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "nickname", nullable = false, length = 40)
    private String nickname;

    @Column(name = "avatar_r2_key", length = 255)
    private String avatarR2Key;

    @Column(name = "bio", length = 500)
    private String bio;

    @Column(name = "last_changed_at", nullable = false)
    private LocalDateTime lastChangedAt;

    @Column(name = "change_count_this_month", nullable = false)
    private Long changeCountThisMonth;

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
        if (this.lastChangedAt == null) {
            this.lastChangedAt = now;
        }
        if (this.changeCountThisMonth == null) {
            this.changeCountThisMonth = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
