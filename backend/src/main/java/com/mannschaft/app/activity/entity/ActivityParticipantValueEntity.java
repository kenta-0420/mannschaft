package com.mannschaft.app.activity.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 参加者レベルのカスタムフィールド値エンティティ。
 */
@Entity
@Table(name = "activity_participant_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityParticipantValueEntity extends BaseEntity {

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false)
    private Long customFieldId;

    @Column(nullable = false, length = 15)
    @Builder.Default
    private String scope = "PARTICIPANT";

    @Column(columnDefinition = "TEXT")
    private String value;
}
