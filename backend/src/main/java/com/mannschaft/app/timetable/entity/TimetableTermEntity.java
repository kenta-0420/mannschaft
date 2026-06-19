package com.mannschaft.app.timetable.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 学期エンティティ。時間割の適用期間（学期・タームなど）を管理する。
 */
@Entity
@Table(name = "timetable_terms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableTermEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false)
    private Integer academicYear;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer sortOrder;

    /**
     * 学期の更新可能フィールドを一括で書き換える（部分更新）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ toBuilder().build() で作り直さないか:</strong>
     * {@link TimetableTermEntity} は {@code @Builder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @Builder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE でなく INSERT が走り、一意制約違反で 500 になる。
     * よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * @param name      新学期名
     * @param startDate 新開始日
     * @param endDate   新終了日
     * @param sortOrder 新並び順
     */
    public void applyUpdate(String name, LocalDate startDate, LocalDate endDate, Integer sortOrder) {
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sortOrder = sortOrder;
    }
}
