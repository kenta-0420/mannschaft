package com.mannschaft.app.timetable.notes.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
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
 * F03.15 コマ単位の個人メモ。
 *
 * <p>TEAM/PERSONAL 両方のスロットに紐付く統一テーブル。slot_id は論理参照（FK なし）。
 * Phase 1 では Entity 定義のみ。CRUD は Phase 3 で実装。</p>
 */
@Entity
@Table(name = "timetable_slot_user_notes")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableSlotUserNoteEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TimetableSlotKind slotKind;

    @Column(nullable = false)
    private Long slotId;

    @Column(columnDefinition = "TEXT")
    private String preparation;

    @Column(columnDefinition = "TEXT")
    private String review;

    @Column(columnDefinition = "TEXT")
    private String itemsToBring;

    @Column(columnDefinition = "TEXT")
    private String freeMemo;

    @Column(columnDefinition = "JSON")
    private String customFields;

    private LocalDate targetDate;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
