package com.mannschaft.app.succession.entity;

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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 封緘解除二者承認エンティティ（F09.15 S1）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.5
 *
 * <p>理事長による起票 → 一次承認 → 二次承認の状態機械を保持する。
 * 起票者・一次承認者・二次承認者の 3 者は必ず別人であることを
 * DB CHECK 制約（{@code chk_ur_three_distinct}）と Service 層で二段検証する。
 *
 * <p>{@code pre_registration_id} は succession ドメイン内 UUIDv7 FK
 * （ON DELETE CASCADE 許可）。
 */
@Entity
@Table(name = "unseal_requests")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UnsealRequestEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /** 同一ドメイン内 UUIDv7 FK → succession_pre_registrations.id */
    @Column(name = "pre_registration_id", nullable = false)
    private UUID preRegistrationId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "request_reason", nullable = false, columnDefinition = "TEXT")
    private String requestReason;

    @Column(name = "first_approver_user_id")
    private Long firstApproverUserId;

    @Column(name = "first_approved_at")
    private LocalDateTime firstApprovedAt;

    @Column(name = "second_approver_user_id")
    private Long secondApproverUserId;

    @Column(name = "second_approved_at")
    private LocalDateTime secondApprovedAt;

    @Column(name = "unseal_completed_at")
    private LocalDateTime unsealCompletedAt;

    /** 72h 自動再封予定日時（二次承認時に NOW() + 72h をセット）。 */
    @Column(name = "auto_reseal_at")
    private LocalDateTime autoResealAt;

    @Column(name = "re_sealed_at")
    private LocalDateTime reSealedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
