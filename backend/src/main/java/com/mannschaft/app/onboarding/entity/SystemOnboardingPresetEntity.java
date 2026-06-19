package com.mannschaft.app.onboarding.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * システムオンボーディングプリセットエンティティ。
 */
@Entity
@Table(name = "system_onboarding_presets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SystemOnboardingPresetEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OnboardingPresetCategory category;

    @Column(length = 1000)
    private String welcomeMessage;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean isOrderEnforced = false;

    private Short deadlineDays;

    @Column(nullable = false, columnDefinition = "JSON")
    private String stepsJson;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @SuperBuilder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 更新リクエストの非 null フィールドを反映する。
     *
     * <p>managed entity を直接ミューテートすることで主キー（BaseEntity の id）と @Version を保持し、
     * save 時に確実に UPDATE となるようにする（toBuilder().build() は id を引き継がず INSERT 化＝行重複を招くため使用しない）。
     */
    public void applyUpdate(
            String name,
            String description,
            OnboardingPresetCategory category,
            String welcomeMessage,
            Boolean isOrderEnforced,
            Short deadlineDays,
            String stepsJson,
            Boolean isActive,
            Integer sortOrder) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (category != null) {
            this.category = category;
        }
        if (welcomeMessage != null) {
            this.welcomeMessage = welcomeMessage;
        }
        if (isOrderEnforced != null) {
            this.isOrderEnforced = isOrderEnforced;
        }
        if (deadlineDays != null) {
            this.deadlineDays = deadlineDays;
        }
        if (stepsJson != null) {
            this.stepsJson = stepsJson;
        }
        if (isActive != null) {
            this.isActive = isActive;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
