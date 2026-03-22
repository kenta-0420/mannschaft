package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ActivityScopeType;
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
import java.time.LocalTime;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private LocalDate activityDate;

    private LocalTime activityTimeStart;

    private LocalTime activityTimeEnd;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "JSON")
    @Builder.Default
    private String fieldValues = "{}";

    @Column(columnDefinition = "JSON")
    private String attachments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ActivityVisibility visibility = ActivityVisibility.MEMBERS_ONLY;

    private Long scheduleId;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 活動記録を更新する。
     */
    public void update(String title, LocalDate activityDate, LocalTime activityTimeStart,
                       LocalTime activityTimeEnd, String description, String fieldValues,
                       String attachments, ActivityVisibility visibility) {
        this.title = title;
        this.activityDate = activityDate;
        this.activityTimeStart = activityTimeStart;
        this.activityTimeEnd = activityTimeEnd;
        this.description = description;
        this.fieldValues = fieldValues;
        this.attachments = attachments;
        this.visibility = visibility;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
