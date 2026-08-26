package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.analytics.FunnelStage;
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
 * ファネルスナップショットエンティティ。
 */
@Entity
@Table(name = "analytics_funnel_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AnalyticsFunnelSnapshotEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private FunnelStage stage;

    @Builder.Default
    @Column(nullable = false)
    private int userCount = 0;
}
