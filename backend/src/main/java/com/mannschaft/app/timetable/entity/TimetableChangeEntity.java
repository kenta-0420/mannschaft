package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.TimetableChangeType;
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

import java.time.LocalDate;

/**
 * 時間割変更エンティティ。特定日の授業変更・休講・振替などを管理する。
 */
@Entity
@Table(name = "timetable_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableChangeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long timetableId;

    @Column(nullable = false)
    private LocalDate targetDate;

    private Integer periodNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TimetableChangeType changeType;

    @Column(length = 100)
    private String subjectName;

    @Column(length = 100)
    private String teacherName;

    @Column(length = 100)
    private String roomName;

    @Column(length = 300)
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyMembers = true;

    private Long createdBy;

    /**
     * 休校日かどうかを判定する。
     *
     * @return DAY_OFF の場合 true
     */
    public boolean isDayOff() {
        return this.changeType == TimetableChangeType.DAY_OFF;
    }
}
