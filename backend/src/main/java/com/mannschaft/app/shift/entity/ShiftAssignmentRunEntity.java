package com.mannschaft.app.shift.entity;

import com.mannschaft.app.shift.AssignmentStrategyType;
import com.mannschaft.app.shift.ShiftAssignmentRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * シフト自動割当実行ログエンティティ。割当アルゴリズムの実行結果を管理する。
 */
@Entity
@Table(name = "shift_assignment_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftAssignmentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AssignmentStrategyType strategy = AssignmentStrategyType.GREEDY_V1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftAssignmentRunStatus status = ShiftAssignmentRunStatus.RUNNING;

    @Column(nullable = false)
    private Long triggeredBy;

    @Column(nullable = false)
    @Builder.Default
    private Integer slotsTotal = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer slotsFilled = 0;

    /** 警告リスト（JSON 文字列） */
    @Column(columnDefinition = "JSON")
    private String warningsJson;

    /** 実行パラメータ（JSON 文字列） */
    @Column(columnDefinition = "JSON")
    private String parametersJson;

    @Column(length = 1000)
    private String errorMessage;

    private Long visualReviewConfirmedBy;

    private LocalDateTime visualReviewConfirmedAt;

    @Column(length = 500)
    private String visualReviewNote;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        this.startedAt = LocalDateTime.now();
    }

    /**
     * 実行成功として完了する。
     */
    public void succeed(int slotsTotal, int slotsFilled, String warningsJson) {
        this.status = ShiftAssignmentRunStatus.SUCCEEDED;
        this.slotsTotal = slotsTotal;
        this.slotsFilled = slotsFilled;
        this.warningsJson = warningsJson;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 実行失敗として完了する。
     */
    public void fail(String errorMessage) {
        this.status = ShiftAssignmentRunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 目視確認済み（確定）にする。
     */
    public void confirmByVisualReview(Long userId, String note) {
        this.status = ShiftAssignmentRunStatus.CONFIRMED;
        this.visualReviewConfirmedBy = userId;
        this.visualReviewConfirmedAt = LocalDateTime.now();
        this.visualReviewNote = note;
    }

    /**
     * 取消にする。
     */
    public void revoke() {
        this.status = ShiftAssignmentRunStatus.REVOKED;
    }
}
