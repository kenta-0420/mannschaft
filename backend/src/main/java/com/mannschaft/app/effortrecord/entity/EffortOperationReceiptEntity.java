package com.mannschaft.app.effortrecord.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.effortrecord.enums.EffortReceiptRouteKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * F06.4.1 冪等 Idempotency-Key の receipt。保持 24 時間・期限削除バッチ対象。
 *
 * <p>private 原本と同じ owner 認可・同じ消去対象（データ設計 §2）。個人情報の長期保管には使わない。</p>
 */
@Entity
@Table(name = "effort_operation_receipts",
        uniqueConstraints = @UniqueConstraint(name = "uq_effort_receipt",
                columnNames = {"route_kind", "route_id", "actor_user_id", "operation_path", "operation_key"}),
        indexes = @Index(name = "idx_effort_receipt_expiry", columnList = "expires_at"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EffortOperationReceiptEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "route_kind", nullable = false, length = 10)
    private EffortReceiptRouteKind routeKind;

    /** OWNER なら owner user ID、TEAM なら team ID（組織には依存しない。2026-11-18 マスター裁定）。 */
    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "operation_key", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String operationKey;

    /** HTTP method 込みの正規化 path。 */
    @Column(name = "operation_path", nullable = false, length = 255)
    private String operationPath;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_json", columnDefinition = "JSON")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
