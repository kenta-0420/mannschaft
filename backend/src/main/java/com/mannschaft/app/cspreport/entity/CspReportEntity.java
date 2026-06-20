package com.mannschaft.app.cspreport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * CSP 違反レポートエンティティ。
 * ブラウザから送信された CSP 違反情報を保持する。
 * report_hash による重複集約で同一違反パターンを 1 レコードに束ねる。
 */
@Entity
@Table(name = "csp_reports")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CspReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 1000)
    private String documentUri;

    @Column(length = 1000)
    private String blockedUri;

    @Column(length = 200)
    private String violatedDirective;

    @Column(length = 200)
    private String effectiveDirective;

    @Column(columnDefinition = "TEXT")
    private String originalPolicy;

    @Column(length = 20)
    private String disposition;

    @Column(length = 500)
    private String scriptSample;

    private Integer statusCode;

    /**
     * SHA-256(violatedDirective + "|" + documentUri + "|" + blockedUri) で生成されるハッシュ。
     * 同一違反パターンを集約するキーとして使用する。
     */
    @Column(nullable = false, length = 64)
    private String reportHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.firstSeenAt == null) {
            this.firstSeenAt = now;
        }
        if (this.lastSeenAt == null) {
            this.lastSeenAt = now;
        }
    }

    /**
     * 同一 report_hash の再発時に発生回数と最終検知日時を更新する。
     */
    public void incrementOccurrence() {
        this.occurrenceCount = this.occurrenceCount + 1;
        this.lastSeenAt = LocalDateTime.now();
    }
}
