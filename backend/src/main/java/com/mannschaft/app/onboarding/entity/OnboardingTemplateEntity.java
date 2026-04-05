package com.mannschaft.app.onboarding.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.onboarding.OnboardingTemplateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * オンボーディングテンプレートエンティティ。
 */
@Entity
@Table(name = "onboarding_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OnboardingTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 1000)
    private String welcomeMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OnboardingTemplateStatus status = OnboardingTemplateStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOrderEnforced = false;

    private Short deadlineDays;

    @Builder.Default
    private Short reminderDaysBefore = 3;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAdminNotifiedOnComplete = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isTimelinePostedOnComplete = false;

    private Long presetId;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * テンプレートをACTIVE状態に変更する。
     */
    public void activate() {
        this.status = OnboardingTemplateStatus.ACTIVE;
    }

    /**
     * テンプレートをARCHIVED状態に変更する。
     */
    public void archive() {
        this.status = OnboardingTemplateStatus.ARCHIVED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * DRAFT状態のテンプレート情報を更新する。
     */
    public void updateDraft(String name, String description, String welcomeMessage,
                            Boolean isOrderEnforced, Short deadlineDays,
                            Short reminderDaysBefore, Boolean isAdminNotifiedOnComplete,
                            Boolean isTimelinePostedOnComplete) {
        this.name = name;
        this.description = description;
        this.welcomeMessage = welcomeMessage;
        this.isOrderEnforced = isOrderEnforced;
        this.deadlineDays = deadlineDays;
        this.reminderDaysBefore = reminderDaysBefore;
        this.isAdminNotifiedOnComplete = isAdminNotifiedOnComplete;
        this.isTimelinePostedOnComplete = isTimelinePostedOnComplete;
    }
}
