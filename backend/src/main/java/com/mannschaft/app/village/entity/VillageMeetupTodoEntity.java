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
 * 寄合宿題（TODO）エンティティ（F17.2 Wave1 ②寄合後半戦・設計書 §4.2.3）。
 *
 * <p>{@code assignee_user_id} が NULL のとき「手挙げ待ち（未割当）」。
 * 村人本人が {@code claim} で自分に割り当て、{@code release} で本人のみ
 * 手放せる（未割当へ戻す）。完了（{@code done_at} セット）は手挙げ者本人＋幹事のみ
 * （設計書 §4.3 権限表）。</p>
 *
 * <p>{@code meetup_id} は同一ドメイン（village）内の参照だが、原則1に従い
 * FK は張らずインデックスのみで整合を保証する。</p>
 */
@Entity
@Table(name = "village_meetup_todos")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupTodoEntity extends UuidV7Entity {

    /** → village_meetups.id（同一ドメイン・FK非付与/index・原則1） */
    @Column(name = "meetup_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID meetupId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 担当者（NULL=手挙げ待ち・未割当。FK非付与・原則1） */
    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    /** 完了時刻（NULL=未完） */
    @Column(name = "done_at")
    private LocalDateTime doneAt;

    /** 作成者（幹事想定・FK非付与・原則1） */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

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
