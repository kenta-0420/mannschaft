package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** V196 billing_api_idempotencies の JPA mapping 骨格。 */
@Entity
@Table(name = "billing_api_idempotencies",
        // V196 の uk_bai_actor_request を mapping にも写す。Entity 由来 DDL の test profile で
        // 同一キー同時予約の UNIQUE 競合が再現できるようにするため（写していないと競合が起きず、
        // 「競合を冪等応答へ写す」経路を検証するテストが原理的に偽 green になる）。
        uniqueConstraints = @UniqueConstraint(name = "uk_bai_actor_request",
                columnNames = {"actor_id", "http_method", "request_path", "idempotency_key"}))
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingApiIdempotencyEntity extends UuidV7Entity {
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "http_method", nullable = false, length = 8)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BillingIdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_json", columnDefinition = "JSON")
    private String responseJson;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
