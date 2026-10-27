package com.mannschaft.app.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 初回本人の技術証拠。コメントや予定本文は保存しない。 */
@Entity
@Table(name="schedule_ranch_witnesses")
@Getter
@NoArgsConstructor
public class ScheduleRanchWitnessEntity extends UuidV7Entity {
    @Column(nullable=false,length=8) private String sourceIdType;
    @Column(nullable=false,columnDefinition="VARBINARY(80)") private byte[] canonicalSourceId;
    @Column(nullable=false,columnDefinition="BIGINT UNSIGNED") private Long recipientUserId;
    @Column(nullable=false,columnDefinition="BIGINT UNSIGNED") private Long scheduleId;
    @Column(nullable=false,length=20) private String kind;
    @Column(columnDefinition="DATETIME(6)") private Instant qualifyingAt;
    @Column(columnDefinition="BINARY(16)") private UUID eventId;
    @Column(nullable=false,columnDefinition="DATETIME(6)") private Instant createdAt;
    @Column(nullable=false,columnDefinition="DATETIME(6)") private Instant updatedAt;
}
