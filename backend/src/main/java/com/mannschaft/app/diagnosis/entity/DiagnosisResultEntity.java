package com.mannschaft.app.diagnosis.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 結果の版・説明を不変スナップショットとして保存する。出生原情報と回答は含めない。 */
@Entity
@Table(name = "diagnosis_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DiagnosisResultEntity extends UuidV7Entity {

    @Column(nullable = false, updatable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30) private DiagnosisMethod method;
    /** 出生結果の計算元プロフィール版。通常診断はnull、出生結果は非負の内部版を保持する。 */
    @Column(updatable = false) private Long sourceProfileRevision;
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT") private String summarySnapshot;
    @Column(nullable = false, updatable = false) private Instant completedAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false, updatable = false) private Instant updatedAt;
}
