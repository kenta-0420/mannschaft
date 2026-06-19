package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.TimetableChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class TimetableChangeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long timetableId;

    @Column(nullable = false)
    private LocalDate targetDate;

    @Column(columnDefinition = "TINYINT UNSIGNED")
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
    @SuperBuilder.Default
    private Boolean notifyMembers = true;

    private Long createdBy;

    /**
     * 臨時変更の更新可能フィールドを部分更新する（null=現値維持セマンティクス）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link TimetableChangeEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE でなく INSERT が走り、行重複 INSERT になる。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * @param subjectName   新科目名（null なら現値維持）
     * @param teacherName   新教員名（null なら現値維持）
     * @param roomName      新教室名（null なら現値維持）
     * @param reason        新理由（null なら現値維持）
     * @param notifyMembers 新通知フラグ（null なら現値維持）
     */
    public void applyUpdate(String subjectName, String teacherName, String roomName,
                            String reason, Boolean notifyMembers) {
        if (subjectName != null) {
            this.subjectName = subjectName;
        }
        if (teacherName != null) {
            this.teacherName = teacherName;
        }
        if (roomName != null) {
            this.roomName = roomName;
        }
        if (reason != null) {
            this.reason = reason;
        }
        if (notifyMembers != null) {
            this.notifyMembers = notifyMembers;
        }
    }

    /**
     * 休校日かどうかを判定する。
     *
     * @return DAY_OFF の場合 true
     */
    public boolean isDayOff() {
        return this.changeType == TimetableChangeType.DAY_OFF;
    }
}
