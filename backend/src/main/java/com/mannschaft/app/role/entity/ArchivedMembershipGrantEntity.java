package com.mannschaft.app.role.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.role.domain.GrantType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * アーカイブ在籍への移行時に取り上げた役職・権限グループ付与の退避記録。
 *
 * <p>{@code user_roles} / {@code user_permission_groups} はソフトデリートも履歴表も持たず、
 * アーカイブ移行（§9.3）で物理削除するため、復元（§9.4）できるようこの表へ退避する。
 * 所有ドメインは role（§5.3.1）。{@code membership_id} への FK は張らない
 * （クロスドメイン FK 禁止。CLAUDE.md アーキテクチャ思想 1）。書き手は {@code RoleService} のみ。</p>
 *
 * <p>行は削除しない。復元しても {@code restoredAt} を埋めるだけにする
 * （「いつ取り上げていつ戻したか」を記録として残す）。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.3 / §5.3.1</p>
 */
@Entity
@Table(name = "archived_membership_grants")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ArchivedMembershipGrantEntity extends UuidV7Entity {

    /** どの memberships 行のアーカイブに紐づくか。FK は張らない（§5.3.1）。 */
    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    /** 退避した時点の membership の世代。復元はこの値が現在世代と一致する行だけを対象にする（§9.4.1.1）。 */
    @Column(name = "archive_generation", nullable = false)
    private Integer archiveGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 20)
    private GrantType grantType;

    /** grant_type=ROLE なら roles.id、PERMISSION_GROUP なら permission_groups.id。FK は張らない。 */
    @Column(name = "grant_ref_id", nullable = false)
    private Long grantRefId;

    /** 元の付与者（user_roles.granted_by / user_permission_groups.assigned_by を退避）。 */
    @Column(name = "granted_by")
    private Long grantedBy;

    /** 取り上げた日時。 */
    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    /** 復元した日時。NULL = 未復元。 */
    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 未復元（アーカイブ中）の付与かを判定する。
     */
    public boolean isRestorable() {
        return this.restoredAt == null;
    }
}
