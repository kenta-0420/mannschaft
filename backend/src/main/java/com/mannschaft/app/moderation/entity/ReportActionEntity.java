package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.moderation.ReportActionType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通報対応アクションエンティティ。通報に対する各種対応履歴を記録する。
 */
@Entity
@Table(name = "report_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReportActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportActionType actionType;

    @Column(nullable = false)
    private Long actionBy;

    @Column(columnDefinition = "TEXT")
    private String note;

    private LocalDateTime freezeUntil;

    @Column(length = 100)
    private String guidelineSection;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
