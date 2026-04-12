package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
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

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約 Phase 3: 募集テンプレートエンティティ。
 * スコープ（チーム／組織）ごとに繰り返し使う募集設定をテンプレートとして保存する。
 * 設計書 §3.x recruitment_templates テーブル参照。
 */
@Entity
@Table(name = "recruitment_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentTemplateEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long categoryId;

    private Long subcategoryId;

    @Column(nullable = false, length = 100)
    private String templateName;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentParticipationType participationType = RecruitmentParticipationType.INDIVIDUAL;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultCapacity = 10;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultMinCapacity = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultDurationMinutes = 90;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultApplicationDeadlineHours = 24;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultAutoCancelHours = 24;

    @Column(nullable = false)
    @Builder.Default
    private Boolean defaultPaymentEnabled = false;

    private Integer defaultPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentVisibility defaultVisibility = RecruitmentVisibility.SCOPE_ONLY;

    @Column(length = 200)
    private String defaultLocation;

    private Long defaultReservationLineId;

    @Column(length = 500)
    private String defaultImageUrl;

    private Long defaultCancellationPolicyId;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    // ===========================================
    // 論理削除
    // ===========================================

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
