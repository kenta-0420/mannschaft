package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.TimetableVisibility;
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
 * 時間割エンティティ。チームに紐づく時間割を管理する。
 */
@Entity
@Table(name = "timetables")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long termId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TimetableStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TimetableVisibility visibility;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveUntil;

    @Column(nullable = false)
    @Builder.Default
    private Boolean weekPatternEnabled = false;

    private LocalDate weekPatternBaseDate;

    @Column(columnDefinition = "JSON")
    private String periodOverride;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 時間割を有効化する。
     */
    public void activate() {
        this.status = TimetableStatus.ACTIVE;
    }

    /**
     * 時間割をアーカイブする。
     */
    public void archive() {
        this.status = TimetableStatus.ARCHIVED;
    }

    /**
     * 時間割を下書きに戻す。
     */
    public void revertToDraft() {
        this.status = TimetableStatus.DRAFT;
    }

    /**
     * 下書き状態かどうかを判定する。
     *
     * @return 下書きの場合 true
     */
    public boolean isDraft() {
        return this.status == TimetableStatus.DRAFT;
    }

    /**
     * 有効状態かどうかを判定する。
     *
     * @return 有効の場合 true
     */
    public boolean isActive() {
        return this.status == TimetableStatus.ACTIVE;
    }

    /**
     * アーカイブ済みかどうかを判定する。
     *
     * @return アーカイブ済みの場合 true
     */
    public boolean isArchived() {
        return this.status == TimetableStatus.ARCHIVED;
    }
}
