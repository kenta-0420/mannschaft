package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 日次ユーザー統計エンティティ。
 */
@Entity
@Table(name = "analytics_daily_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AnalyticsDailyUsersEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default
    @Column(nullable = false)
    private int newUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int activeUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int payingUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int churnedUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int reactivatedUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int totalUsers = 0;
}
