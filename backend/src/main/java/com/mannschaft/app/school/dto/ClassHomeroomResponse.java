package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 学級担任設定レスポンス DTO。 */
@Getter
@Builder
public class ClassHomeroomResponse {

    private Long id;
    private Long teamId;
    private Long homeroomTeacherUserId;
    private List<Long> assistantTeacherUserIds;
    private Integer academicYear;
    private LocalDate effectiveFrom;
    private LocalDate effectiveUntil;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClassHomeroomResponse from(ClassHomeroomEntity entity, List<Long> assistantIds) {
        return ClassHomeroomResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .homeroomTeacherUserId(entity.getHomeroomTeacherUserId())
                .assistantTeacherUserIds(assistantIds)
                .academicYear(entity.getAcademicYear())
                .effectiveFrom(entity.getEffectiveFrom())
                .effectiveUntil(entity.getEffectiveUntil())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
