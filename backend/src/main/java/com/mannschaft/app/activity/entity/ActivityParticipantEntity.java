package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ParticipationType;
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

/**
 * 活動参加者エンティティ。
 */
@Entity
@Table(name = "activity_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityParticipantEntity extends BaseEntity {

    @Column(nullable = false)
    private Long activityResultId;

    private Long userId;

    private Long memberProfileId;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(length = 20)
    private String memberNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private ParticipationType participationType = ParticipationType.OTHER;

    private Integer minutesPlayed;

    @Column(length = 500)
    private String note;
}
