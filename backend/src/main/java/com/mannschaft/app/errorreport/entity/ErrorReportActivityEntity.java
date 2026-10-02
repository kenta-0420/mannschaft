package com.mannschaft.app.errorreport.entity;

import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F12.5 Phase 2 — エラーレポート操作履歴・コメントエンティティ。
 * ステータス変更・担当者変更・コメント追加・AI分析実行・GitHub Issue 作成等を記録する。
 *
 * <p>{@code metadata_json} には種別ごとの追加情報を JSON 文字列で保存する
 * （例: WORKFLOW_CHANGED の {@code {"from": "INVESTIGATION_STARTED", "to": "FIX_IN_PROGRESS"}}）。</p>
 *
 * <p>{@code actorId IS NULL && metadata_json.system != true} → 「退会した管理者」、
 * {@code actorId IS NULL && metadata_json.system == true} → 「システム自動」と表示する。</p>
 */
@PersonalData(category = "error_reports")
@Entity
@Table(name = "error_report_activities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ErrorReportActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long errorReportId;

    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ErrorReportActivityType activityType;

    @Column(length = 2000)
    private String content;

    @Column(length = 2000)
    private String metadataJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
