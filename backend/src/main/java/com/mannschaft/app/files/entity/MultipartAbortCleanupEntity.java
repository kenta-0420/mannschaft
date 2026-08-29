package com.mannschaft.app.files.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** multipart abort補償の専用台帳。元sessionとは独立したTxで保存する。 */
@Entity
@Table(name = "multipart_abort_cleanups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MultipartAbortCleanupEntity extends UuidV7Entity {
    @Column(name = "upload_id", nullable = false, unique = true, length = 255)
    private String uploadId;
    @Column(name = "r2_key", nullable = false, length = 500)
    private String r2Key;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "feature", nullable = false, length = 30)
    private String feature;
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;
    @Column(name = "claimed_at")
    private Instant claimedAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;
}
