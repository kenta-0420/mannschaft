package com.mannschaft.app.onboarding.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.onboarding.OnboardingStepType;
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

/**
 * オンボーディングテンプレートステップエンティティ。
 */
@Entity
@Table(name = "onboarding_template_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OnboardingTemplateStepEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OnboardingStepType stepType;

    private Long referenceId;

    @Column(length = 500)
    private String referenceUrl;

    private Short deadlineOffsetDays;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
