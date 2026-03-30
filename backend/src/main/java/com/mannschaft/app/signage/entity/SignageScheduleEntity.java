package com.mannschaft.app.signage.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.signage.SignageDayOfWeek;
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

import java.time.LocalTime;

/**
 * デジタルサイネージ スケジュールエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 */
@Entity
@Table(name = "signage_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SignageScheduleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long screenId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SignageDayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    /** 表示するslot_idの配列（JSON文字列）。 */
    @Column(nullable = false, columnDefinition = "JSON")
    private String slotIds;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;
}
