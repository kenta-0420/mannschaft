package com.mannschaft.app.incident.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * メンテナンススケジュールエンティティ。
 */
@Entity(name = "IncidentMaintenanceScheduleEntity")
@Table(name = "incident_maintenance_schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MaintenanceScheduleEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 100)
    private String cronExpression;

    @Column(nullable = false)
    private LocalDate nextExecutionDate;

    @Column(length = 20)
    private String defaultAssigneeType;

    private Long defaultAssigneeUserId;

    @Column(length = 100)
    private String defaultAssigneeExternalName;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Version
    private Long version;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * スケジュールを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * スケジュールを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * 次回実行日を更新する。
     */
    public void updateNextExecutionDate(LocalDate nextDate) {
        this.nextExecutionDate = nextDate;
    }

    /**
     * CRON式を更新する。
     */
    public void updateCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }
}
