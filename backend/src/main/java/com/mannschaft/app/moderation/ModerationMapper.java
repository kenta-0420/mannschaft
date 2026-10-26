package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * モデレーション機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ModerationMapper {

    @Mapping(target = "targetType", expression = "java(entity.getTargetType().name())")
    @Mapping(target = "reason", expression = "java(entity.getReason().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ReportResponse toReportResponse(ContentReportEntity entity);

    List<ReportResponse> toReportResponseList(List<ContentReportEntity> entities);
}
