package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ownerを作らず、管理主体とkeyに結び付ける保存済み成功ACK。 */
@Entity
@Table(name = "ranch_admin_commands")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchAdminCommandEntity extends RanchEntity {
    @Column(name = "actor_user_id", nullable = false, columnDefinition = "bigint unsigned")
    private Long actorUserId;
    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;
    @Column(name = "command_type", nullable = false, length = 30)
    private String commandType;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "body_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] bodyHash;
    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private String resultJson;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public byte[] getBodyHash() { return bodyHash.clone(); }
}