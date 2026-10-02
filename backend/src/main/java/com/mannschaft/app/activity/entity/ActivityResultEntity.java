package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class ActivityResultEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /** テンプレート ID。DRAFT（下書き）は最小項目作成のため NULL 許容。 */
    private Long templateId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private LocalDate activityDate;

    private LocalTime activityTimeStart;

    private LocalTime activityTimeEnd;

    @Column(length = 300)
    private String location;

    private Long venueId;

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

    /**
     * ライフサイクル状態（F06.4 下書き対応）。
     *
     * <p>{@code @Builder.Default = PUBLISHED} とすることで、status を明示指定しない
     * 従来の作成経路（{@code ActivityResultService#createActivity}）は「作成即公開」の
     * 挙動を維持する（後方互換）。DRAFT 作成は専用経路で明示的に status を指定する。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ActivityStatus status = ActivityStatus.PUBLISHED;

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
     * fieldValues のみを更新する（議事録確定など、メタ情報のみ変更する用途）。
     */
    public void updateFieldValues(String fieldValues) {
        this.fieldValues = fieldValues;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 公開可能かどうかを判定する（DRAFT のときのみ true）。
     */
    public boolean isPublishable() {
        return this.status == ActivityStatus.DRAFT;
    }

    /**
     * 下書きを公開する（DRAFT → PUBLISHED）。
     */
    public void publish() {
        this.status = ActivityStatus.PUBLISHED;
    }
}
