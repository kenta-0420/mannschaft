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
@Table(name="timeline_ranch_admin_commands",uniqueConstraints=@UniqueConstraint(columnNames={"actor_user_id","idempotency_key"}))
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@SuperBuilder(toBuilder=true)
public class TimelineRanchAdminCommandEntity extends UuidV7Entity {
    @Column(name="actor_user_id",nullable=false,columnDefinition="BIGINT UNSIGNED") private Long actorUserId;
    @Column(name="idempotency_key",nullable=false,columnDefinition="BINARY(16)") private UUID idempotencyKey;
    @Column(name="command_type",nullable=false,columnDefinition="VARCHAR(30)") private String commandType;
    @Column(name="body_hash",nullable=false,columnDefinition="BINARY(32)") private byte[] bodyHash;
    @Column(name="result_json",nullable=false,columnDefinition="JSON") private String resultJson;
    @Column(name="completed_at",nullable=false,columnDefinition="DATETIME(6)") private Instant completedAt;
    @Column(name="created_at",nullable=false,columnDefinition="DATETIME(6)") private Instant createdAt;
    @Column(name="updated_at",nullable=false,columnDefinition="DATETIME(6)") private Instant updatedAt;
}
