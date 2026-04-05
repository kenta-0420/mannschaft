package com.mannschaft.app.payment.entity;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * チームサブスクリプションエンティティ。チームの課金プラン状態を管理する。
 */
@Entity
@Table(name = "team_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamSubscriptionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PlanType planType = PlanType.FREE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(length = 200)
    private String stripeSubscriptionId;

    private LocalDate currentPeriodStart;

    private LocalDate currentPeriodEnd;

    private LocalDateTime cancelledAt;

    /**
     * プラン種別。
     */
    public enum PlanType {
        FREE,
        MODULE,
        PACKAGE,
        ORGANIZATION
    }

    /**
     * サブスクリプションステータス。
     */
    public enum SubscriptionStatus {
        ACTIVE,
        CANCELLED,
        EXPIRED,
        PAST_DUE
    }

    /**
     * 有料プランかどうかを判定する。
     */
    public boolean isPaidPlan() {
        return planType != PlanType.FREE && status == SubscriptionStatus.ACTIVE;
    }
}
