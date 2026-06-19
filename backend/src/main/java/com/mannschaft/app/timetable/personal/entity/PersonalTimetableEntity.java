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
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
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
    @SuperBuilder.Default
    private Boolean weekPatternEnabled = false;

    private LocalDate weekPatternBaseDate;

    @Column(length = 500)
    private String notes;

    private LocalDateTime deletedAt;

    /**
     * 個人時間割の更新可能フィールドを部分更新する（null=現値維持セマンティクス）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link PersonalTimetableEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE でなく INSERT が走り、行重複 INSERT になる。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * <p>各フィールドは「リクエスト値が非 null なら採用、null なら現値を維持」の部分更新セマンティクス。
     * ただし effectiveFrom / effectiveUntil / weekPatternEnabled / weekPatternBaseDate は
     * null 合体済みの値を受け取る（呼び出し側で解決済み）。
     *
     * @param name                新名称（null なら現値維持）
     * @param academicYear        新学年度（null なら現値維持）
     * @param termLabel           新学期ラベル（null なら現値維持）
     * @param effectiveFrom       新適用開始日（解決済み・必ず非 null）
     * @param effectiveUntil      新適用終了日（解決済み・nullable）
     * @param visibility          新公開範囲（null なら現値維持）
     * @param weekPatternEnabled  新週パターン有効フラグ（解決済み・必ず非 null）
     * @param weekPatternBaseDate 新週パターン基準日（null なら現値維持）
     * @param notes               新メモ（null なら現値維持）
     */
    public void applyUpdate(String name, Integer academicYear, String termLabel,
                            LocalDate effectiveFrom, LocalDate effectiveUntil,
                            PersonalTimetableVisibility visibility,
                            boolean weekPatternEnabled, LocalDate weekPatternBaseDate,
                            String notes) {
        // 解決済みの値を常にセット
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.weekPatternEnabled = weekPatternEnabled;
        this.weekPatternBaseDate = weekPatternBaseDate;
        // null の場合は現値維持
        if (name != null) {
            this.name = name;
        }
        if (academicYear != null) {
            this.academicYear = academicYear;
        }
        if (termLabel != null) {
            this.termLabel = termLabel;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        if (notes != null) {
            this.notes = notes;
        }
    }

    /**
     * visibility を直接書き換える（ShareTargetService による自動切替用）。
     *
     * <p>PersonalTimetableShareTargetService が共有先追加／解除時に visibility を
     * 自動切替する際に使用する。{@link #applyUpdate} と分離することで、
     * フルフィールド更新と visibility のみの切替を明確に区別する。
     *
     * @param visibility 新公開範囲
     */
    public void applyVisibility(PersonalTimetableVisibility visibility) {
        this.visibility = visibility;
    }

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
