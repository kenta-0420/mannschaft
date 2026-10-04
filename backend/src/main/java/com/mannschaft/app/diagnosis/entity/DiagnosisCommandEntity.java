package com.mannschaft.app.diagnosis.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 本人のUUID命令と成功応答を保存し再送による結果の増殖を防ぐ。 */
@Entity
@Table(name = "diagnosis_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DiagnosisCommandEntity extends UuidV7Entity {

    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false, updatable = false) private UUID commandId;
    @Column(nullable = false, updatable = false, length = 64) private String requestHash;
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT") private String responseSnapshot;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false, updatable = false) private Instant updatedAt;
}
