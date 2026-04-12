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
    // ステータス操作
    // ===========================================

    /** テンプレートを論理削除（アーカイブ）する。 */
    public void archive() {
        this.deletedAt = LocalDateTime.now();
    }

    /** 論理削除を行う（後方互換用エイリアス）。 */
    public void softDelete() {
        archive();
    }

    // ===========================================
    // 編集
    // ===========================================

    /** テンプレートの情報を更新する。null の場合は変更しない。 */
    public void update(
            String templateName,
            String title,
            String description,
            Long categoryId,
            Long subcategoryId,
            RecruitmentParticipationType participationType,
            Integer defaultCapacity,
            Integer defaultMinCapacity,
            Integer defaultDurationMinutes,
            Integer defaultApplicationDeadlineHours,
            Integer defaultAutoCancelHours,
            Boolean defaultPaymentEnabled,
            Integer defaultPrice,
            RecruitmentVisibility defaultVisibility,
            String defaultLocation,
            Long defaultReservationLineId,
            String defaultImageUrl,
            Long defaultCancellationPolicyId
    ) {
        if (templateName != null) this.templateName = templateName;
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (categoryId != null) this.categoryId = categoryId;
        if (subcategoryId != null) this.subcategoryId = subcategoryId;
        if (participationType != null) this.participationType = participationType;
        if (defaultCapacity != null) this.defaultCapacity = defaultCapacity;
        if (defaultMinCapacity != null) this.defaultMinCapacity = defaultMinCapacity;
        if (defaultDurationMinutes != null) this.defaultDurationMinutes = defaultDurationMinutes;
        if (defaultApplicationDeadlineHours != null) this.defaultApplicationDeadlineHours = defaultApplicationDeadlineHours;
        if (defaultAutoCancelHours != null) this.defaultAutoCancelHours = defaultAutoCancelHours;
        if (defaultPaymentEnabled != null) this.defaultPaymentEnabled = defaultPaymentEnabled;
        if (defaultPrice != null) this.defaultPrice = defaultPrice;
        if (defaultVisibility != null) this.defaultVisibility = defaultVisibility;
        if (defaultLocation != null) this.defaultLocation = defaultLocation;
        if (defaultReservationLineId != null) this.defaultReservationLineId = defaultReservationLineId;
        if (defaultImageUrl != null) this.defaultImageUrl = defaultImageUrl;
        if (defaultCancellationPolicyId != null) this.defaultCancellationPolicyId = defaultCancellationPolicyId;
    }
}
