package com.mannschaft.app.facility.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 施設予約設定エンティティ。スコープ別の施設予約設定を管理する。
 */
@Entity
@Table(name = "facility_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FacilitySettingsEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean requiresApproval = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxBookingsPerDayPerUser = 2;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowStripePayment = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer cancellationDeadlineHours = 24;

    @Column(nullable = false)
    @Builder.Default
    private Boolean noShowPenaltyEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer noShowPenaltyThreshold = 3;

    @Column(nullable = false)
    @Builder.Default
    private Integer noShowPenaltyDays = 30;

    /**
     * 設定を更新する。
     */
    public void update(Boolean requiresApproval, Integer maxBookingsPerDayPerUser,
                       Boolean allowStripePayment, Integer cancellationDeadlineHours,
                       Boolean noShowPenaltyEnabled, Integer noShowPenaltyThreshold,
                       Integer noShowPenaltyDays) {
        this.requiresApproval = requiresApproval;
        this.maxBookingsPerDayPerUser = maxBookingsPerDayPerUser;
        this.allowStripePayment = allowStripePayment;
        this.cancellationDeadlineHours = cancellationDeadlineHours;
        this.noShowPenaltyEnabled = noShowPenaltyEnabled;
        this.noShowPenaltyThreshold = noShowPenaltyThreshold;
        this.noShowPenaltyDays = noShowPenaltyDays;
    }
}
