package com.mannschaft.app.errorreport.entity;

import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F12.5 Phase 2 — AI 分析履歴エンティティ。
 * Claude Haiku 4.5 によるエラー解析の入力・出力を永続化する。
 *
 * <p>{@code raw_response} は 30日経過後に NULL 化（クリーンアップ対象）。</p>
 *
 * <p>{@code status} は VARCHAR カラムで "SUCCESS" / "FAILED" のいずれかを保持する
 * （ENUM 化は将来検討）。</p>
 */
@PersonalData(category = "error_reports")
@Entity
@Table(name = "error_report_ai_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ErrorReportAiAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long errorReportId;

    @Column(nullable = false, length = 100)
    private String modelName;

    @Column(nullable = false)
    @Builder.Default
    private Integer promptTokens = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer completionTokens = 0;

    @Column(length = 2000)
    private String estimatedCause;

    @Column(length = 2000)
    private String fixProposal;

    @Column(length = 1000)
    private String impactAssessment;

    @Column(length = 1000)
    private String suggestedFiles;

    /**
     * クリーンアップで NULL 化されるため Setter を提供する。
     */
    @Setter
    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "SUCCESS";

    @Column(length = 500)
    private String errorMessage;

    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
