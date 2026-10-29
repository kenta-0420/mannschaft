package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * M が所有する通知予約。publication 削除後も scalar descriptor を保持する。
 * 文字列は descriptorVersion の判定後に閉じた型へ変換する。JPA enum 変換は行わない。
 * WAITING_RECOVERY は影響通知が存在する episode の復旧評価待ち。relay 対象には含めない。
 * 業務 SPI はこの行の読取 TX を終えた後に呼び、CLOSED_COMPLETE と M pending=0 で昇格する。
 * RELAYED は fanout enqueue の受付であり、通知配送完了を意味しない。
 */
@Entity
@Table(name = "module_event_notice_outbox")
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModuleEventNoticeOutboxEntity extends UuidV7Entity {
    // UUID 主キーから変換せず、DB が正規の通知参照 ID を採番する。
    @Generated(event = EventType.INSERT)
    @Column(name = "notice_ref", nullable = false, insertable = false, updatable = false,
            columnDefinition = "BIGINT NOT NULL AUTO_INCREMENT UNIQUE")
    private Long noticeRef;

    @Column(name = "notice_key", nullable = false, unique = true)
    private UUID noticeKey;

    @Column(name = "resolver_key", nullable = false, length = 100)
    private String resolverKey;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "phase", nullable = false, length = 16)
    private String phase;

    @Column(name = "audience", nullable = false, length = 32)
    private String audience;

    @Column(name = "descriptor_version", nullable = false)
    private int descriptorVersion;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    // RELAYED でも最終 token を保持し、retention の世代比較に用いる。
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "relayed_at")
    private Instant relayedAt;

    @Column(name = "dead_at")
    private Instant deadAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
