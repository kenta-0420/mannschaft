package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.queue.QueueScopeType;
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
 * 順番待ち設定エンティティ。スコープ単位の順番待ち設定を管理する。
 */
@Entity
@Table(name = "queue_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QueueSettingsEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    @Builder.Default
    private Short noShowTimeoutMinutes = 5;

    @Column(nullable = false)
    @Builder.Default
    private Boolean noShowPenaltyEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Short noShowPenaltyThreshold = 3;

    @Column(nullable = false)
    @Builder.Default
    private Short noShowPenaltyDays = 14;

    @Column(nullable = false)
    @Builder.Default
    private Short maxActiveTicketsPerUser = 1;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowGuestQueue = true;

    @Column(nullable = false)
    @Builder.Default
    private Short almostReadyThreshold = 3;

    @Column(nullable = false)
    @Builder.Default
    private Short holdExtensionMinutes = 5;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoAdjustServiceMinutes = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean displayBoardPublic = false;

    /**
     * 設定を更新する。
     */
    public void update(Short noShowTimeoutMinutes, Boolean noShowPenaltyEnabled,
                       Short noShowPenaltyThreshold, Short noShowPenaltyDays,
                       Short maxActiveTicketsPerUser, Boolean allowGuestQueue,
                       Short almostReadyThreshold, Short holdExtensionMinutes,
                       Boolean autoAdjustServiceMinutes, Boolean displayBoardPublic) {
        this.noShowTimeoutMinutes = noShowTimeoutMinutes;
        this.noShowPenaltyEnabled = noShowPenaltyEnabled;
        this.noShowPenaltyThreshold = noShowPenaltyThreshold;
        this.noShowPenaltyDays = noShowPenaltyDays;
        this.maxActiveTicketsPerUser = maxActiveTicketsPerUser;
        this.allowGuestQueue = allowGuestQueue;
        this.almostReadyThreshold = almostReadyThreshold;
        this.holdExtensionMinutes = holdExtensionMinutes;
        this.autoAdjustServiceMinutes = autoAdjustServiceMinutes;
        this.displayBoardPublic = displayBoardPublic;
    }
}
