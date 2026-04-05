package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsDailyUsersEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default
    private int newUsers = 0;

    @Builder.Default
    private int activeUsers = 0;

    @Builder.Default
    private int payingUsers = 0;

    @Builder.Default
    private int churnedUsers = 0;

    @Builder.Default
    private int reactivatedUsers = 0;

    @Builder.Default
    private int totalUsers = 0;
}
