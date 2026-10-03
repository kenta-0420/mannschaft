package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_commandsの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_commands")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchCommandEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;
    @Column(name = "command_type", nullable = false, length = 30)
    private String commandType;
    @Column(name = "body_hash", nullable = false, length = 32)
    private byte[] bodyHash;
    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private String resultJson;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
}
