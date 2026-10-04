package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** 運営が公開した報酬停止期間。過去factは発生時点で判定する。 */
@Entity
@Table(name = "ranch_reward_pause_periods")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchRewardPausePeriodEntity extends RanchEntity {
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at")
    private Instant endsAt;
    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    public void closeAt(Instant now) {
        if (now == null || endsAt != null || !now.isAfter(startsAt)) {
            throw new IllegalArgumentException("報酬停止期間を終了できません");
        }
        endsAt = now;
    }
}
