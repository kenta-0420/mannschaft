package com.mannschaft.app.role.entity;

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

/**
 * オーナー委譲（ADMIN 権限移譲）の承諾型オファーエンティティ（F01.2 / 2026-07-18 承諾型化）。
 *
 * <p>発行者が打診（PENDING 作成）し、指名相手の承諾（accept）で初めて委譲を実行する。
 * 主キーは UUIDv7（原則6・新規テーブル）。{@code team_id}/{@code organization_id}（team/org ドメイン）・
 * {@code issued_by}/{@code target_user_id}（user ドメイン）はいずれも本テーブル（role ドメイン）から見て
 * 別ドメイン参照のため FK は張らず INDEX のみ（原則1）。</p>
 *
 * <p>{@code status} は VARCHAR + アプリ層検証（MySQL ENUM にはしない）。
 * 取り得る値: {@code PENDING} / {@code ACCEPTED} / {@code DECLINED} / {@code EXPIRED} / {@code CANCELLED}。</p>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/01_db_design.md #ownership_transfer_offers</p>
 */
@Entity
@Table(name = "ownership_transfer_offers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class OwnershipTransferOfferEntity extends UuidV7Entity {

    /** ステータス既定値（PENDING）。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 委譲対象がチームの場合に設定（組織委譲時は NULL）。FK 張らない（原則1）。 */
    @Column(name = "team_id")
    private Long teamId;

    /** 委譲対象が組織の場合に設定（チーム委譲時は NULL）。FK 張らない（原則1）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 発行者（現 ADMIN）の user ID。FK 張らない（原則1）。 */
    @Column(name = "issued_by", nullable = false)
    private Long issuedBy;

    /** 指名相手（承諾できる唯一のユーザー）の user ID。FK 張らない（原則1）。 */
    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    /** ステータス（PENDING/ACCEPTED/DECLINED/EXPIRED/CANCELLED）。VARCHAR + アプリ層検証。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 有効期限（発行から7日を既定）。超過は EXPIRED。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 承諾日時（ACCEPTED 時のみ）。 */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /** 辞退/取消/期限確定の処理日時。 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

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
        if (this.status == null) {
            this.status = STATUS_PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
