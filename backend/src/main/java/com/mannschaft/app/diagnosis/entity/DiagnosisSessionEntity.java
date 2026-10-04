package com.mannschaft.app.diagnosis.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 本人診断の設問・回答を開始時の版で保持する。 */
@Entity
@Table(name = "diagnosis_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DiagnosisSessionEntity extends UuidV7Entity {

    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DiagnosisStatus status;
    @Column(nullable = false, length = 80) private String questionnaireVersion;
    @Column(nullable = false, length = 80) private String scoringVersion;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String questionsSnapshot;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String answersSnapshot;
    @Column(nullable = false) private long answerRevision;
    @Version @Column(nullable = false) private Long version;
    private UUID resultId;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
}
