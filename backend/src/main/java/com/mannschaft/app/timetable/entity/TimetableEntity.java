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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class TimetableEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long termId;

    @Column(nullable = false, length = 200)
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
     * 時間割の更新可能フィールドを部分更新する（null=現値維持セマンティクス）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link TimetableEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE でなく INSERT が走り、行重複 INSERT になる。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * @param name                 新名称（null なら現値維持）
     * @param visibility           新公開範囲（null なら現値維持）
     * @param effectiveFrom        新適用開始日（null なら現値維持）
     * @param effectiveUntil       新適用終了日（null なら現値維持）
     * @param weekPatternEnabled   新週パターン有効フラグ（null なら現値維持）
     * @param weekPatternBaseDate  新週パターン基準日（null なら現値維持）
     * @param periodOverride       新時限オーバーライド JSON（null なら現値維持）
     * @param notes                新メモ（null なら現値維持）
     */
    public void applyUpdate(String name, TimetableVisibility visibility,
                            LocalDate effectiveFrom, LocalDate effectiveUntil,
                            Boolean weekPatternEnabled, LocalDate weekPatternBaseDate,
                            String periodOverride, String notes) {
        if (name != null) {
            this.name = name;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        if (effectiveFrom != null) {
            this.effectiveFrom = effectiveFrom;
        }
        if (effectiveUntil != null) {
            this.effectiveUntil = effectiveUntil;
        }
        if (weekPatternEnabled != null) {
            this.weekPatternEnabled = weekPatternEnabled;
        }
        if (weekPatternBaseDate != null) {
            this.weekPatternBaseDate = weekPatternBaseDate;
        }
        if (periodOverride != null) {
            this.periodOverride = periodOverride;
        }
        if (notes != null) {
            this.notes = notes;
        }
    }

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
