package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.RecallSessionCommandType;
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
import java.util.UUID;

/** private ACK 履歴。比較 hash は HTTP・報酬 outbox・export へ出さず源 purge で削除する。 */
@Entity
@Table(name="reflection_recall_commands",uniqueConstraints=@UniqueConstraint(name="uk_recall_command",columnNames={"user_id","idempotency_key"}))
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@SuperBuilder
public class RecallSessionCommandEntity extends UuidV7Entity {
    @Column(name="user_id",nullable=false,columnDefinition="BIGINT UNSIGNED") private Long userId;
    @Column(name="idempotency_key",nullable=false,columnDefinition="BINARY(16)") private UUID idempotencyKey;
    @Column(name="session_id",nullable=false,columnDefinition="BINARY(16)") private UUID sessionId;
    @Enumerated(EnumType.STRING) @Column(name="command_type",nullable=false,length=20) private RecallSessionCommandType commandType;
    @Column(name="body_hash",nullable=false,columnDefinition="BINARY(32)") private byte[] bodyHash;
    @Column(name="result_json",nullable=false,columnDefinition="JSON") private String resultJson;
    @Column(name="completed_at",nullable=false) private Instant completedAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
