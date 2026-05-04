package com.mannschaft.app.school.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 出席要件規程エンティティ。組織またはチームスコープで出席要件を定義する。 */
@Entity
@Table(name = "attendance_requirement_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AttendanceRequirementRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 組織スコープ（team_id と排他） */
    private Long organizationId;

    /** チームスコープ（organization_id と排他） */
    private Long teamId;

    /** 対象学期（NULLなら年度通算） */
    private Long termId;

    /** 学年度（例: 2025） */
    @Column(nullable = false)
    private Short academicYear;

    /** 規程カテゴリ */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequirementCategory category;

    /** 規程名（例: 3年進級要件） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 規程の説明 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 最小出席率（%） */
    @Column(precision = 5, scale = 2)
    private BigDecimal minAttendanceRate;

    /** 最大欠席日数 */
    private Short maxAbsenceDays;

    /** 最大欠席率（%） */
    @Column(precision = 5, scale = 2)
    private BigDecimal maxAbsenceRate;

    /** 保健室登校を出席扱いにするか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean countSickBayAsPresent = true;

    /** 別室登校を出席扱いにするか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean countSeparateRoomAsPresent = true;

    /** 図書室登校を出席扱いにするか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean countLibraryAsPresent = true;

    /** オンライン登校を出席扱いにするか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean countOnlineAsPresent = true;

    /** 家庭学習を公欠扱いにするか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean countHomeLearningAsOfficialAbsence = false;

    /** 遅刻N回で欠席1日換算（0=換算なし） */
    @Column(nullable = false)
    @Builder.Default
    private Byte countLateAsAbsenceThreshold = 0;

    /** 警告発火しきい値（%） */
    @Column(precision = 5, scale = 2)
    private BigDecimal warningThresholdRate;

    /** 有効開始日 */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** 有効終了日（NULLなら無期限） */
    private LocalDate effectiveUntil;

    /** VIOLATION確定時に自動で保護者通知するか（デフォルト false） */
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private boolean notifyViolationToGuardianAutomatically = false;

    /** 作成日時 */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 更新日時 */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
