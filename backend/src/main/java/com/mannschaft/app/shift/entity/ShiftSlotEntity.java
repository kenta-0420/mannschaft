package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * シフト枠エンティティ。特定日時のシフト枠を管理する。
 */
@Entity
@Table(name = "shift_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private LocalDate slotDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private Long positionId;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer requiredCount = 1;

    @Column(columnDefinition = "JSON")
    private String assignedUserIds;

    @Column(length = 200)
    private String note;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * シフト枠の更新可能フィールドを一括で書き換える（部分更新）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * {@link ShiftSlotEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE ではなく INSERT が走り、行重複が発生する
     * （本メソッド導入の動機）。よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * <p>各フィールドは「リクエスト値が非 null なら採用、null なら現値を維持」の部分更新セマンティクス。
     *
     * @param slotDate        新スロット日付（null なら現値維持）
     * @param startTime       新開始時刻（null なら現値維持）
     * @param endTime         新終了時刻（null なら現値維持）
     * @param positionId      新ポジションID（null なら現値維持）
     * @param requiredCount   新必要人数（null なら現値維持）
     * @param assignedUserIds 新割当ユーザーID JSON（null なら現値維持）
     * @param note            新メモ（null なら現値維持）
     */
    public void applyUpdate(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                            Long positionId, Integer requiredCount, String assignedUserIds, String note) {
        if (slotDate != null) {
            this.slotDate = slotDate;
        }
        if (startTime != null) {
            this.startTime = startTime;
        }
        if (endTime != null) {
            this.endTime = endTime;
        }
        if (positionId != null) {
            this.positionId = positionId;
        }
        if (requiredCount != null) {
            this.requiredCount = requiredCount;
        }
        if (assignedUserIds != null) {
            this.assignedUserIds = assignedUserIds;
        }
        if (note != null) {
            this.note = note;
        }
    }

    /**
     * 割当ユーザーID リスト（JSON）を直接更新する。
     *
     * <p>シフト自動割当・差分割当など、割当ユーザーのみを更新するユースケース向け。
     *
     * @param assignedUserIds 新割当ユーザーID JSON（null 許容・NULL で割当クリア）
     */
    public void updateAssignedUserIds(String assignedUserIds) {
        this.assignedUserIds = assignedUserIds;
    }
}
