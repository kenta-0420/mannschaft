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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F12.5 Phase 2 — 個別発生ログエンティティ。
 * error_reports は集約レコード、こちらは1発生 = 1行を保持し、
 * タイムライン表示や統計用に使用する。
 *
 * <p>保持期間: 30日（または最新100件）。{@code ErrorReportCleanupService} で削除する。</p>
 */
@PersonalData(category = "error_reports")
@Entity
@Table(name = "error_report_occurrences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ErrorReportOccurrenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long errorReportId;

    private Long userId;

    @Column(nullable = false, length = 2048)
    private String pageUrl;

    @Column(length = 500)
    private String userAgent;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 36)
    private String requestId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
