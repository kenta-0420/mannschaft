package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.ChartBodyMarkResponse;
import com.mannschaft.app.chart.dto.ChartFormulaResponse;
import com.mannschaft.app.chart.dto.ChartPhotoResponse;
import com.mannschaft.app.chart.dto.ChartRecordResponse;
import com.mannschaft.app.chart.dto.ChartRecordSummaryResponse;
import com.mannschaft.app.chart.dto.CustomFieldResponse;
import com.mannschaft.app.chart.dto.CustomFieldValueResponse;
import com.mannschaft.app.chart.dto.IntakeFormResponse;
import com.mannschaft.app.chart.dto.RecordTemplateResponse;
import com.mannschaft.app.chart.dto.SectionSettingResponse;
import com.mannschaft.app.chart.entity.ChartBodyMarkEntity;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartCustomValueEntity;
import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import com.mannschaft.app.chart.entity.ChartIntakeFormEntity;
import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.entity.ChartSectionSettingEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * カルテ機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ChartMapper {

    @Mapping(target = "customerDisplayName", source = "customerDisplayName")
    @Mapping(target = "staffDisplayName", source = "staffDisplayName")
    @Mapping(target = "sectionsEnabled", source = "sectionsEnabled")
    @Mapping(target = "customFields", source = "customFields")
    @Mapping(target = "photos", source = "photos")
    @Mapping(target = "formulas", source = "formulas")
    @Mapping(target = "bodyMarks", source = "bodyMarks")
    @Mapping(target = "id", source = "entity.id")
    @Mapping(target = "teamId", source = "entity.teamId")
    @Mapping(target = "customerUserId", source = "entity.customerUserId")
    @Mapping(target = "staffUserId", source = "entity.staffUserId")
    @Mapping(target = "visitDate", source = "entity.visitDate")
    @Mapping(target = "chiefComplaint", source = "entity.chiefComplaint")
    @Mapping(target = "treatmentNote", source = "entity.treatmentNote")
    @Mapping(target = "nextRecommendation", source = "entity.nextRecommendation")
    @Mapping(target = "nextVisitRecommendedDate", source = "entity.nextVisitRecommendedDate")
    @Mapping(target = "allergyInfo", source = "entity.allergyInfo")
    @Mapping(target = "isSharedToCustomer", source = "entity.isSharedToCustomer")
    @Mapping(target = "isPinned", source = "entity.isPinned")
    @Mapping(target = "version", source = "entity.version")
    @Mapping(target = "createdAt", source = "entity.createdAt")
    ChartRecordResponse toChartRecordResponse(
            ChartRecordEntity entity,
            String customerDisplayName,
            String staffDisplayName,
            Map<String, Boolean> sectionsEnabled,
            List<CustomFieldValueResponse> customFields,
            List<ChartPhotoResponse> photos,
            List<ChartFormulaResponse> formulas,
            List<ChartBodyMarkResponse> bodyMarks);

    default ChartRecordSummaryResponse toSummaryResponse(
            ChartRecordEntity entity,
            String customerDisplayName,
            String staffDisplayName,
            int photoCount) {
        return new ChartRecordSummaryResponse(
                entity.getId(),
                entity.getTeamId(),
                entity.getCustomerUserId(),
                customerDisplayName,
                entity.getStaffUserId(),
                staffDisplayName,
                entity.getVisitDate(),
                entity.getChiefComplaint(),
                entity.getIsSharedToCustomer(),
                entity.getIsPinned(),
                entity.getAllergyInfo() != null && !entity.getAllergyInfo().isEmpty(),
                photoCount,
                entity.getCreatedAt()
        );
    }

    default ChartPhotoResponse toPhotoResponse(ChartPhotoEntity entity, String signedUrl, LocalDateTime expiresAt) {
        return new ChartPhotoResponse(
                entity.getId(),
                entity.getPhotoType(),
                signedUrl,
                expiresAt,
                entity.getOriginalFilename(),
                entity.getFileSizeBytes(),
                entity.getNote(),
                entity.getIsSharedToCustomer()
        );
    }

    @Mapping(source = "XPosition", target = "xPosition")
    @Mapping(source = "YPosition", target = "yPosition")
    ChartBodyMarkResponse toBodyMarkResponse(ChartBodyMarkEntity entity);

    List<ChartBodyMarkResponse> toBodyMarkResponseList(List<ChartBodyMarkEntity> entities);

    ChartFormulaResponse toFormulaResponse(ChartFormulaEntity entity);

    List<ChartFormulaResponse> toFormulaResponseList(List<ChartFormulaEntity> entities);

    IntakeFormResponse toIntakeFormResponse(ChartIntakeFormEntity entity);

    List<IntakeFormResponse> toIntakeFormResponseList(List<ChartIntakeFormEntity> entities);

    SectionSettingResponse toSectionSettingResponse(ChartSectionSettingEntity entity);

    List<SectionSettingResponse> toSectionSettingResponseList(List<ChartSectionSettingEntity> entities);

    CustomFieldResponse toCustomFieldResponse(ChartCustomFieldEntity entity);

    List<CustomFieldResponse> toCustomFieldResponseList(List<ChartCustomFieldEntity> entities);

    default CustomFieldValueResponse toCustomFieldValueResponse(ChartCustomValueEntity value, ChartCustomFieldEntity field) {
        return new CustomFieldValueResponse(
                field.getId(),
                field.getFieldName(),
                value.getValue()
        );
    }

    RecordTemplateResponse toRecordTemplateResponse(ChartRecordTemplateEntity entity);

    List<RecordTemplateResponse> toRecordTemplateResponseList(List<ChartRecordTemplateEntity> entities);
}
