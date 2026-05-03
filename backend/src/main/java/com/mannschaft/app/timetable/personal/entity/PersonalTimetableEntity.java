package com.mannschaft.app.timetable.personal.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
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
 * F03.15 個人時間割マスター。
 */
@Entity
@Table(name = "personal_timetables")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PersonalTimetableEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String name;

    private Integer academicYear;

    @Column(length = 50)
    private String termLabel;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PersonalTimetableStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PersonalTimetableVisibility visibility;

    @Column(nullable = false)
    @Builder.Default
    private Boolean weekPatternEnabled = false;

    private LocalDate weekPatternBaseDate;

    @Column(length = 500)
    private String notes;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * DRAFT → ACTIVE。
     */
    public void activate() {
        this.status = PersonalTimetableStatus.ACTIVE;
    }

    /**
     * ACTIVE → ARCHIVED。
     */
    public void archive() {
        this.status = PersonalTimetableStatus.ARCHIVED;
    }

    /**
     * ARCHIVED → DRAFT。
     */
    public void revertToDraft() {
        this.status = PersonalTimetableStatus.DRAFT;
    }

    public boolean isDraft() {
        return this.status == PersonalTimetableStatus.DRAFT;
    }

    public boolean isActive() {
        return this.status == PersonalTimetableStatus.ACTIVE;
    }

    public boolean isArchived() {
        return this.status == PersonalTimetableStatus.ARCHIVED;
    }
}
