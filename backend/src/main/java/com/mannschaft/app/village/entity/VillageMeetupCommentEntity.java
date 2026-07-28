package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 寄合コメントエンティティ（F17.2 Wave1 ②寄合後半戦・設計書 §4.2.2）。
 *
 * <p>CONFIRMED 状態の寄合に対する村人の会話。論理削除（{@code deleted_at}）は
 * 投稿者本人＋村長/長老のみ可能（設計書 §4.4・AC-09）。</p>
 *
 * <p>{@code meetup_id} は同一ドメイン（village）内の参照だが、原則1に従い
 * FK は張らずインデックスのみで整合を保証する。</p>
 */
@Entity
@Table(name = "village_meetup_comments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupCommentEntity extends UuidV7Entity {

    /** → village_meetups.id（同一ドメイン・FK非付与/index・原則1） */
    @Column(name = "meetup_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID meetupId;

    /** 投稿者ユーザーID（FK非付与・原則1） */
    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** 論理削除 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
