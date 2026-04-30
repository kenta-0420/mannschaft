package com.mannschaft.app.school.entity;

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

/** 学級担任マッピング（クラスチームと担任の対応）。 */
@Entity
@Table(name = "class_homerooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ClassHomeroomEntity extends BaseEntity {

    /** FK → teams.id（クラスチーム） */
    @Column(nullable = false)
    private Long teamId;

    /** FK → users.id（学級担任） */
    @Column(nullable = false)
    private Long homeroomTeacherUserId;

    /** 副担任配列 [123, 456]（最大3名、JSON） */
    @Column(columnDefinition = "JSON")
    private String assistantTeacherUserIds;

    /** 年度 */
    @Column(nullable = false)
    private Integer academicYear;

    /** 有効開始日 */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** 有効終了日（NULL=現役） */
    private LocalDate effectiveUntil;

    /** 作成者 */
    @Column(nullable = false)
    private Long createdBy;
}
