package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** ライブラリ管理の publication に対して、試行回数・lease・隔離を保持する補助行。 */
@Entity
@Table(name = "event_publication_attempts", uniqueConstraints =
        @UniqueConstraint(name = "uq_event_publication_attempts_publication_id", columnNames = "publication_id"))
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventPublicationAttemptEntity extends UuidV7Entity {
    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ModuleEventAttemptStatus status;
    @Column(name = "claim_token", nullable = false)
    private UUID claimToken;
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
    @Column(name = "quarantined_at")
    private LocalDateTime quarantinedAt;
    @Column(name = "failure_code", length = 64)
    private String failureCode;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
