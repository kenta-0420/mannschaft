package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** ライブラリ管理の publication に対して、試行回数・lease・隔離を保持する補助行。 */
@Entity
@Table(name = "event_publication_attempts", uniqueConstraints =
        @UniqueConstraint(name = "uq_event_publication_attempts_publication_id", columnNames = "publication_id"),
        indexes = @Index(name = "idx_event_publication_attempts_episode",
                columnList = "resolver_key,operation_id,episode_id,scope_type,scope_id,organization_id"))
@Check(name = "chk_event_publication_attempts_impact_binding", constraints = """
        (resolver_key IS NULL AND operation_id IS NULL AND episode_id IS NULL
            AND scope_type IS NULL AND scope_id IS NULL AND organization_id IS NULL)
        OR (resolver_key IS NOT NULL AND resolver_key <> ''
            AND operation_id IS NOT NULL AND episode_id IS NOT NULL AND scope_type IS NOT NULL
            AND scope_id IS NOT NULL AND scope_id > 0 AND scope_type IN ('TEAM','ORGANIZATION')
            AND (organization_id IS NULL OR organization_id > 0)
            AND (scope_type = 'TEAM' OR (organization_id IS NOT NULL AND organization_id = scope_id)))
        """)
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventPublicationAttemptEntity extends UuidV7Entity {
    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ModuleEventAttemptStatus status;
    @Column(name = "claim_token", nullable = false)
    private UUID claimToken;
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "quarantined_at")
    private Instant quarantinedAt;
    @Column(name = "failure_code", length = 64)
    private String failureCode;
    // 業務が明示した識別子だけを保存する。未接続のイベントは全列nullのままにする。
    @Column(name = "resolver_key", length = 100)
    private String resolverKey;
    @Column(name = "operation_id")
    private UUID operationId;
    @Column(name = "episode_id")
    private UUID episodeId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope_type", length = 16)
    private ModuleEventNoticeScope scopeType;
    @Column(name = "scope_id")
    private Long scopeId;
    @Column(name = "organization_id")
    private Long organizationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** claimだけでは試行を増やさない。workerの短TX受理が計数点になる。 */
    void claim(UUID token, Instant now, Instant lease) {
        status = ModuleEventAttemptStatus.IN_PROGRESS;
        claimToken = token;
        lastAttemptAt = now;
        leaseUntil = lease;
        updatedAt = now;
    }

    boolean admitReplay(UUID expectedToken, Instant now) {
        if (!expectedToken.equals(claimToken) || status != ModuleEventAttemptStatus.IN_PROGRESS
                || leaseUntil == null || !leaseUntil.isAfter(now) || retryCount >= 10) return false;
        retryCount++;
        lastAttemptAt = now;
        updatedAt = now;
        return true;
    }

    void renewLease(Instant now, Instant lease) {
        lastAttemptAt = now;
        leaseUntil = lease;
        updatedAt = now;
    }

    void quarantine(String code, Instant now) {
        status = ModuleEventAttemptStatus.QUARANTINED;
        quarantinedAt = now;
        leaseUntil = null;
        failureCode = code;
        updatedAt = now;
    }

    void pending(String code, Instant now) {
        status = ModuleEventAttemptStatus.PENDING;
        leaseUntil = null;
        failureCode = code;
        updatedAt = now;
    }

    /** 初回失敗は再配信回数に含めず、次の回収対象として残す。 */
    public void recordInitialFailure(Instant attemptedAt) {
        status = ModuleEventAttemptStatus.PENDING;
        lastAttemptAt = attemptedAt;
        leaseUntil = null;
        failureCode = "LISTENER_FAILURE";
        updatedAt = attemptedAt;
    }
}
