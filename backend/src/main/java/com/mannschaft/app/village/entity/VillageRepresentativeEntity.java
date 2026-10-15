package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村代表委任エンティティ（F17.1 Phase 2）。
 *
 * <p>HEADMAN がチーム/組織メンバーシップに対し、個別ユーザーへ
 * 「代表として投稿/発言する権限」を委譲した記録を表す。</p>
 *
 * <p>Phase 1 では「チーム/組織 ADMIN は自動的に代表」運用だが、
 * Phase 2 で本テーブルによる個別委任を追加する。
 * 取消しは {@link #revokedAt} による論理削除で履歴を残す。</p>
 *
 * <p><b>原則1 遵守:</b> {@link #representativeUserId} / {@link #grantedByUserId} /
 * {@link #revokedByUserId} は users への FK を張らない（クロスドメインFK禁止）。</p>
 *
 * <p><b>原則7 適用外:</b> 全テナント横断ドメインゆえ {@code organization_id} を持たず、
 * Repository も標準 {@code JpaRepository} を継承する。</p>
 *
 * @see com.mannschaft.app.village.repository.VillageRepresentativeRepository
 */
@Entity
@Table(name = "village_representatives")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageRepresentativeEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** FK → village_memberships.id（同一ドメイン・CASCADE） */
    @Column(name = "membership_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID membershipId;

    /** 代表権を委任されたユーザーID（FK 張らない／原則1） */
    @Column(name = "representative_user_id", nullable = false)
    private Long representativeUserId;

    /** 委任を実行した HEADMAN ユーザーID（FK 張らない／原則1） */
    @Column(name = "granted_by_user_id", nullable = false)
    private Long grantedByUserId;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    /** 委任取消し日時（論理削除）。NULL なら現役。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 取消しを実行したユーザーID（FK 張らない／原則1） */
    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;

    /** 委任理由メモ */
    @Column(name = "note", length = 200)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
