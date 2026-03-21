package com.mannschaft.app.matching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.matching.ActivityType;
import com.mannschaft.app.matching.MatchCategory;
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
 * マッチング推薦通知設定エンティティ。
 */
@Entity
@Table(name = "match_notification_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MatchNotificationPreferenceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(length = 2)
    private String prefectureCode;

    @Column(length = 5)
    private String cityCode;

    @Enumerated(EnumType.STRING)
    private ActivityType activityType;

    @Enumerated(EnumType.STRING)
    private MatchCategory category;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /**
     * 通知設定を更新する。
     */
    public void update(String prefectureCode, String cityCode, ActivityType activityType,
                       MatchCategory category, Boolean isEnabled) {
        this.prefectureCode = prefectureCode;
        this.cityCode = cityCode;
        this.activityType = activityType;
        this.category = category;
        this.isEnabled = isEnabled;
    }
}
