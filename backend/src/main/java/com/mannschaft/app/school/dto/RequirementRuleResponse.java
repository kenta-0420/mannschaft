package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.RequirementCategory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** F03.13 Phase 10: 出席要件規程 単件レスポンス DTO。 */
@Getter
@Builder
public class RequirementRuleResponse {

    private Long id;
    private Long organizationId;
    private Long teamId;
    private Long termId;
    private Short academicYear;
    private RequirementCategory category;
    private String name;
    private String description;
    private BigDecimal minAttendanceRate;
    private Short maxAbsenceDays;
    private BigDecimal maxAbsenceRate;
    private Boolean countSickBayAsPresent;
    private Boolean countSeparateRoomAsPresent;
    private Boolean countLibraryAsPresent;
    private Boolean countOnlineAsPresent;
    private Boolean countHomeLearningAsOfficialAbsence;
    private Byte countLateAsAbsenceThreshold;
    private BigDecimal warningThresholdRate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * エンティティから DTO を生成する。
     *
     * @param e 出席要件規程エンティティ
     * @return レスポンス DTO
     */
    public static RequirementRuleResponse from(AttendanceRequirementRuleEntity e) {
        return RequirementRuleResponse.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .teamId(e.getTeamId())
                .termId(e.getTermId())
                .academicYear(e.getAcademicYear())
                .category(e.getCategory())
                .name(e.getName())
                .description(e.getDescription())
                .minAttendanceRate(e.getMinAttendanceRate())
                .maxAbsenceDays(e.getMaxAbsenceDays())
                .maxAbsenceRate(e.getMaxAbsenceRate())
                .countSickBayAsPresent(e.getCountSickBayAsPresent())
                .countSeparateRoomAsPresent(e.getCountSeparateRoomAsPresent())
                .countLibraryAsPresent(e.getCountLibraryAsPresent())
                .countOnlineAsPresent(e.getCountOnlineAsPresent())
                .countHomeLearningAsOfficialAbsence(e.getCountHomeLearningAsOfficialAbsence())
                .countLateAsAbsenceThreshold(e.getCountLateAsAbsenceThreshold())
                .warningThresholdRate(e.getWarningThresholdRate())
                .effectiveFrom(e.getEffectiveFrom())
                .effectiveUntil(e.getEffectiveUntil())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
