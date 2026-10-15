package com.mannschaft.app.errorreport;

import com.mannschaft.app.errorreport.dto.ErrorReportResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * エラーレポート Entity - DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ErrorReportMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "severity", expression = "java(entity.getSeverity().name())")
    @Mapping(target = "workflowStage",
            expression = "java(entity.getWorkflowStage() != null ? entity.getWorkflowStage().name() : null)")
    // assigneeName / latestAiAnalysis はサービス層で別途解決する
    @Mapping(target = "assigneeName", ignore = true)
    @Mapping(target = "latestAiAnalysis", ignore = true)
    ErrorReportResponse toResponse(ErrorReportEntity entity);

    List<ErrorReportResponse> toResponseList(List<ErrorReportEntity> entities);
}
