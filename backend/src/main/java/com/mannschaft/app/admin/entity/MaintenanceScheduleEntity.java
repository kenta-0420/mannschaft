package com.mannschaft.app.admin.entity;

import com.mannschaft.app.admin.MaintenanceMode;
import com.mannschaft.app.admin.MaintenanceStatus;
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

import java.time.LocalDateTime;

/**
 * メンテナンススケジュールエンティティ。計画メンテナンスの管理を行う。
 */
@Entity
@Table(name = "maintenance_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MaintenanceScheduleEntity extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MaintenanceMode mode = MaintenanceMode.MAINTENANCE;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MaintenanceStatus status = MaintenanceStatus.SCHEDULED;

    @Column(nullable = false)
    private Long createdBy;

    /**
     * メンテナンス情報を更新する。
     */
    public void update(String title, String message, MaintenanceMode mode,
                       LocalDateTime startsAt, LocalDateTime endsAt) {
        this.title = title;
        this.message = message;
        this.mode = mode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(MaintenanceStatus newStatus) {
        this.status = newStatus;
    }
}
