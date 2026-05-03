package com.mannschaft.app.timetable.notes.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.15 ユーザー定義カスタムメモ項目（最大10件/ユーザー）。Phase 1 では Entity のみ。
 */
@Entity
@Table(name = "timetable_slot_user_note_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimetableSlotUserNoteFieldEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(length = 100)
    private String placeholder;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxLength = 2000;
}
