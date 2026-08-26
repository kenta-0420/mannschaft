package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 共有予定の明示対象者。userId はクロスドメイン参照のため DB FK を張らない。 */
@Entity
@Table(name = "schedule_targets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScheduleTargetEntity extends UuidV7Entity {

    /** schedule ドメイン内の親。DDL で schedules への CASCADE FK を持つ。 */
    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    /** users ドメインへの論理参照。退会・GDPR時はサービス層で扱う。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;
}
