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
import java.time.LocalDate;
import java.util.UUID;

/** TL所有の私有配送行。本文・出生原情報は含めない。更新は短い源TXで行う。 */
@Entity
@Table(name="timeline_ranch_witnesses",uniqueConstraints={@UniqueConstraint(columnNames={"source_id_type","canonical_source_id"}),@UniqueConstraint(columnNames={"recipient_user_id","content_week","content_version","content_digest"})})
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
@SuperBuilder(toBuilder=true)
public class TimelineRanchWitnessEntity extends UuidV7Entity {
    @Column(name="source_id_type",nullable=false,columnDefinition="VARCHAR(8)") private String sourceIdType;
    @Column(name="canonical_source_id",nullable=false,columnDefinition="VARBINARY(80)") private byte[] canonicalSourceId;
    @Column(name="recipient_user_id",nullable=false,columnDefinition="BIGINT UNSIGNED") private Long recipientUserId;
    @Column(name="kind",nullable=false,columnDefinition="VARCHAR(20)") private String kind;
    @Column(name="qualifying_at",nullable=true,columnDefinition="DATETIME(6)") private Instant qualifyingAt;
    @Column(name="event_id",nullable=true,columnDefinition="BINARY(16)") private UUID eventId;
    @Column(name="content_week",nullable=true,columnDefinition="DATE") private LocalDate contentWeek;
    @Column(name="content_version",nullable=true,columnDefinition="VARCHAR(64)") private String contentVersion;
    @Column(name="content_key_id",nullable=true,columnDefinition="BINARY(32)") private byte[] contentKeyId;
    @Column(name="content_digest",nullable=true,columnDefinition="BINARY(32)") private byte[] contentDigest;
    @Column(name="created_at",nullable=false,columnDefinition="DATETIME(6)") private Instant createdAt;
    @Column(name="updated_at",nullable=false,columnDefinition="DATETIME(6)") private Instant updatedAt;
}
