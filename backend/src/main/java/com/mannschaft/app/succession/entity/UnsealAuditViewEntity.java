package com.mannschaft.app.succession.entity;

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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 開封中閲覧履歴エンティティ（F09.15 S1・append-only）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.6
 *
 * <p>封緘解除中（UNSEALED）の事前登録を閲覧した際の監査記録。
 * append-only テーブルであり、UPDATE / DELETE はアプリ層で禁止する
 * （論理削除カラムは規約上保持するが運用上 deleted_at は常に NULL）。
 *
 * <p>{@code unseal_request_id} は succession ドメイン内 UUIDv7 FK
 * （ON DELETE CASCADE 許可）。
 */
@Entity
@Table(name = "unseal_audit_views")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UnsealAuditViewEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 同一ドメイン内 UUIDv7 FK → unseal_requests.id */
    @Column(name = "unseal_request_id", nullable = false)
    private UUID unsealRequestId;

    @Column(name = "viewer_user_id", nullable = false)
    private Long viewerUserId;

    @Column(name = "viewed_at", nullable = false)
    private LocalDateTime viewedAt;

    /** 閲覧元 IP（IPv6 対応）。 */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** MDC requestId（追跡用）。 */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.viewedAt == null) {
            this.viewedAt = now;
        }
    }
}
