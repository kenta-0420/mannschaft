package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 寄合候補日エンティティ（F17.1 Phase 3-β）。
 *
 * <p>寄合 1 件あたり複数の候補日を持つ。各候補日に対して投票が紐づく。
 * 親 {@link VillageMeetupEntity} 削除時は同一ドメイン CASCADE で削除される。</p>
 */
@Entity
@Table(name = "village_meetup_candidate_dates")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupCandidateDateEntity extends UuidV7Entity {

    /** FK → village_meetups.id（同一ドメイン CASCADE） */
    @Column(name = "meetup_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID meetupId;

    /** 候補日 */
    @Column(name = "candidate_date", nullable = false)
    private LocalDate candidateDate;

    /** 候補の時刻（任意・NULL は終日）。#2357 */
    @Column(name = "candidate_time")
    private LocalTime candidateTime;

    /** 表示順 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }
}
