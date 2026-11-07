package com.mannschaft.app.timeline.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** TL所有の私有配送行。本文・出生原情報は含めない。更新は短い源TXで行う。 */
@Entity
@Table(name="timeline_ranch_outboxes",uniqueConstraints=@UniqueConstraint(columnNames={"event_type","canonical_key"}))
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@SuperBuilder(toBuilder=true)
public class TimelineRanchOutboxEntity extends UuidV7Entity {
    @Column(name="schema_version",nullable=false,columnDefinition="INT") private int schemaVersion;
    @Column(name="event_type",nullable=false,columnDefinition="VARCHAR(40)") private String eventType;
    @Column(name="scope_type",nullable=false,columnDefinition="VARCHAR(20)") private String scopeType;
    @Column(name="scope_id_type",nullable=true,columnDefinition="VARCHAR(8)") private String scopeIdType;
    @Column(name="canonical_scope_id",nullable=true,columnDefinition="VARBINARY(80)") private byte[] canonicalScopeId;
    @Column(name="recipient_user_id",nullable=false,columnDefinition="BIGINT UNSIGNED") private Long recipientUserId;
    @Column(name="canonical_key",nullable=false,columnDefinition="VARBINARY(240)") private byte[] canonicalKey;
    @Column(name="payload_json",nullable=false,columnDefinition="JSON") private String payloadJson;
    @Column(name="occurred_at",nullable=false,columnDefinition="DATETIME(6)") private Instant occurredAt;
    @Column(name="status",nullable=false,columnDefinition="VARCHAR(20)") private String status;
    @Column(name="terminal_outcome",nullable=true,columnDefinition="VARCHAR(40)") private String terminalOutcome;
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name="attempt_count",nullable=false,columnDefinition="INT") private int attemptCount;
    @Column(name="next_attempt_at",nullable=false,columnDefinition="DATETIME(6)") private Instant nextAttemptAt;
    @Column(name="lease_token",nullable=true,columnDefinition="BINARY(16)") private UUID leaseToken;
    @Column(name="lease_expires_at",nullable=true,columnDefinition="DATETIME(6)") private Instant leaseExpiresAt;
    @Column(name="last_error_code",nullable=true,columnDefinition="VARCHAR(80)") private String lastErrorCode;
    @Column(name="acked_at",nullable=true,columnDefinition="DATETIME(6)") private Instant ackedAt;
    @Column(name="created_at",nullable=false,columnDefinition="DATETIME(6)") private Instant createdAt;
    @Column(name="updated_at",nullable=false,columnDefinition="DATETIME(6)") private Instant updatedAt;
}
