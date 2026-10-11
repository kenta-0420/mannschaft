package com.mannschaft.app.common.outbox;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * 送り手ドメインごとの通知 outbox 表に共通する列（docs/architecture/notification_outbox.md §3）。
 *
 * <p>各ドメインはこれを継承した Entity と、自ドメインの Repository を持つ（表は送り手ドメインに置く。
 * common の単一表にしない理由は設計書 §2）。時刻はすべて起きた瞬間なので {@link Instant}（UTC で保存）。</p>
 *
 * <p>書き込み・claim・印付けは native SQL（{@code INSERT ... ON DUPLICATE KEY UPDATE id = id}・
 * {@code FOR UPDATE SKIP LOCKED}・{@code claim_token} 一致の条件付き UPDATE）で行う。本 Entity は
 * スキーマの宣言（試練の DB は Entity から生成される）と読み取りに使う。</p>
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public abstract class AbstractNotificationOutboxEntity extends UuidV7Entity {

    /** fan-out の冪等キー（{@code notification_fanout_jobs.source_event_uuid} と同じ値）。 */
    @Column(name = "idempotency_key", nullable = false, columnDefinition = "BINARY(16)")
    private UUID idempotencyKey;

    /** {@code FANOUT} / {@code FANOUT_WITH_AUDIENCE}（通知ドメインの {@code NotificationOutboxMessageKind} の名前）。 */
    @Column(name = "message_kind", nullable = false, length = 32)
    private String messageKind;

    /** {@code payload_json} の版。 */
    @Column(name = "payload_version", nullable = false, columnDefinition = "SMALLINT UNSIGNED NOT NULL")
    private Integer payloadVersion;

    @Column(name = "payload_json", nullable = false, columnDefinition = "JSON")
    private String payloadJson;

    /** 運用・監視用の写し。 */
    @Column(name = "notification_type", nullable = false, length = 64)
    private String notificationType;

    /** テナント（運用用。クロスドメイン FK なし）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** claim の世代。印付けはこの値が一致するときだけ当たる。 */
    @Column(name = "claim_token", columnDefinition = "BINARY(16)")
    private UUID claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "relayed_at")
    private Instant relayedAt;

    @Column(name = "dead_at")
    private Instant deadAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreateOutbox() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdateOutbox() {
        updatedAt = Instant.now();
    }
}
