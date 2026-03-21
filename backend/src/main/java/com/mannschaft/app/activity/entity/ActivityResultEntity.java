package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ActivityVisibility;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 活動記録エンティティ。
 */
@Entity
@Table(name = "activity_results")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityResultEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    private Long templateId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDate activityDate;

    @Column(length = 200)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ActivityVisibility visibility = ActivityVisibility.MEMBERS_ONLY;

    @Column(length = 500)
    private String coverImageUrl;

    private Long scheduleEventId;

    @Column(nullable = false)
    @Builder.Default
    private Integer participantCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 活動記録を更新する。
     */
    public void update(String title, String description, LocalDate activityDate,
                       String location, ActivityVisibility visibility, String coverImageUrl) {
        this.title = title;
        this.description = description;
        this.activityDate = activityDate;
        this.location = location;
        this.visibility = visibility;
        this.coverImageUrl = coverImageUrl;
    }

    /**
     * 参加者数をインクリメントする。
     */
    public void incrementParticipantCount(int count) {
        this.participantCount += count;
    }

    /**
     * 参加者数をデクリメントする。
     */
    public void decrementParticipantCount(int count) {
        this.participantCount = Math.max(0, this.participantCount - count);
    }

    /**
     * 閲覧数をインクリメントする。
     */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
