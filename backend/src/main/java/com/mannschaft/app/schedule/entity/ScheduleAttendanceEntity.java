package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
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
 * スケジュール出欠エンティティ。各ユーザーの出欠回答を管理する。
 */
@Entity
@Table(name = "schedule_attendances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleAttendanceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Column(length = 500)
    private String comment;

    private LocalDateTime respondedAt;

    /**
     * 出欠を回答する。初回回答時のみ respondedAt をセットする。
     *
     * @param newStatus 新しい出欠ステータス
     * @param comment   コメント（nullable）
     */
    public void respond(AttendanceStatus newStatus, String comment) {
        this.status = newStatus;
        this.comment = comment;
        if (this.respondedAt == null) {
            this.respondedAt = LocalDateTime.now();
        }
    }
}
