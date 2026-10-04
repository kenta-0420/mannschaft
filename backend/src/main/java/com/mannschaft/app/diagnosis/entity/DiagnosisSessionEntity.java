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
    /** 部分回答の保存時に回答版を進め、旧同点選択を無効化する。 */
    public void updateAnswers(String encodedAnswers, Instant now) {
        this.answersSnapshot = encodedAnswers;
        this.answerRevision = Math.addExact(this.answerRevision, 1);
        this.status = DiagnosisStatus.STARTED;
        this.updatedAt = now;
    }
    /** 同じ回答版の本人選択を保存し、未解決軸を保留する。 */
    public void awaitTieBreak(String encodedAnswers, Instant now) {
        this.answersSnapshot = encodedAnswers;
        this.status = DiagnosisStatus.TIE_BREAK_REQUIRED;
        this.updatedAt = now;
    }
    /** 同domain TXで永久結果を作成した後にsessionを確定する。 */
    public void complete(String encodedAnswers, UUID resultId, Instant now) {
        this.answersSnapshot = encodedAnswers;
        this.resultId = java.util.Objects.requireNonNull(resultId);
        this.status = DiagnosisStatus.COMPLETED;
        this.updatedAt = now;
    }
    /** 保留とは区別して取消を保存する。 */
    public void cancel(Instant now) {
        this.status = DiagnosisStatus.CANCELLED;
        this.updatedAt = now;
    }

}
