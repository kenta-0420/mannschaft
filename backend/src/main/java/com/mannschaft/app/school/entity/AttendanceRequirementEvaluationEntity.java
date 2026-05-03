package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 出席要件評価結果エンティティ（F03.13 Phase 12）。 */
@Entity
@Table(name = "attendance_requirement_evaluations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AttendanceRequirementEvaluationEntity extends BaseEntity {

    /** FK→attendance_requirement_rules.id */
    @Column(nullable = false)
    private Long requirementRuleId;

    /** 評価対象生徒 FK→users.id */
    @Column(nullable = false)
    private Long studentUserId;

    /** 元となった集計 FK→student_attendance_summaries.id */
    @Column(nullable = false)
    private Long summaryId;

    /** 評価ステータス */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private EvaluationStatus status = EvaluationStatus.OK;

    /** 評価時点の出席率（%） */
    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal currentAttendanceRate = BigDecimal.ZERO;

    /** あと何日休めるか（0以下=違反） */
    @Column(nullable = false)
    @Builder.Default
    private int remainingAllowedAbsences = 0;

    /** 評価実施日時 */
    @Column(nullable = false)
    private LocalDateTime evaluatedAt;

    /** 通知済みユーザーIDのJSONアレイ */
    @Column(columnDefinition = "JSON")
    private String notifiedUserIds;

    /** 違反解消日時 */
    private LocalDateTime resolvedAt;

    /** 解消理由 */
    @Column(length = 512)
    private String resolutionNote;

    /** 解消した教員ID FK→users.id */
    private Long resolverUserId;

    /** 論理削除日時 */
    private LocalDateTime deletedAt;

    /** 評価ステータス */
    public enum EvaluationStatus {
        /** 問題なし */
        OK,
        /** 警告（要注意） */
        WARNING,
        /** 危険（違反リスク高） */
        RISK,
        /** 違反（要件未達） */
        VIOLATION
    }

    /**
     * 評価を解消済みにする。
     *
     * @param resolverUserId 解消した教員のユーザーID
     * @param resolutionNote 解消理由
     */
    public void resolve(Long resolverUserId, String resolutionNote) {
        this.resolvedAt = LocalDateTime.now();
        this.resolverUserId = resolverUserId;
        this.resolutionNote = resolutionNote;
    }

    /**
     * 評価が解消済みかどうかを返す。
     *
     * @return 解消済みなら true
     */
    public boolean isResolved() {
        return resolvedAt != null;
    }
}
