package com.mannschaft.app.notification.credit.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 通知月次使用量集計エンティティ。
 *
 * <p>(organization_id, month, source_type) の UNIQUE 制約により、1組織1ヶ月1発生源ごとに集計する。</p>
 */
@Entity
@Table(name = "notification_monthly_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationMonthlyUsageEntity extends BaseEntity {

    /** 組織ID */
    @Column(nullable = false)
    private Long organizationId;

    /** 集計月（YYYY-MM-01） */
    @Column(nullable = false)
    private LocalDate month;

    /** 通知発生源 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationSourceType sourceType;

    /** 合計使用通数 */
    @Column(nullable = false)
    @Builder.Default
    private Long usedCount = 0L;

    /** 無料枠から消費した通数 */
    @Column(nullable = false)
    @Builder.Default
    private Long freeCount = 0L;

    /** クレジット残高から消費した通数 */
    @Column(nullable = false)
    @Builder.Default
    private Long creditCount = 0L;

    /** 猶予期間中の送信通数（翌月1日に相殺予定） */
    @Column(nullable = false)
    @Builder.Default
    private Long graceCount = 0L;

    /**
     * 通数を加算する。
     *
     * @param used   合計通数
     * @param free   無料枠から消費した通数
     * @param credit クレジットから消費した通数
     * @param grace  猶予期間中の通数
     */
    public void addUsage(long used, long free, long credit, long grace) {
        this.usedCount += used;
        this.freeCount += free;
        this.creditCount += credit;
        this.graceCount += grace;
    }
}
