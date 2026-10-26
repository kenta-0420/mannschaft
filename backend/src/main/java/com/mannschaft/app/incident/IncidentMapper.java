package com.mannschaft.app.incident;

import com.mannschaft.app.incident.entity.IncidentCategoryEntity;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.incident.service.IncidentCategoryService.IncidentCategoryResponse;
import com.mannschaft.app.incident.service.IncidentService.IncidentResponse;
import com.mannschaft.app.incident.service.IncidentService.IncidentSummaryResponse;
import com.mannschaft.app.incident.service.MaintenanceScheduleService.MaintenanceScheduleResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;


/**
 * インシデントドメインのエンティティ → DTO 変換マッパー（MapStruct 自動生成）。
 * componentModel="spring" により Spring Bean として注入可能。
 */
@Mapper(componentModel = "spring")
public interface IncidentMapper {

    // ========================================
    // IncidentEntity → IncidentResponse
    // ========================================

    /**
     * IncidentEntity をフル詳細レスポンスに変換する。
     */
    @Mapping(target = "id",               source = "id")
    @Mapping(target = "scopeType",        source = "scopeType")
    @Mapping(target = "scopeId",          source = "scopeId")
    @Mapping(target = "categoryId",       source = "categoryId")
    @Mapping(target = "title",            source = "title")
    @Mapping(target = "description",      source = "description")
    @Mapping(target = "status",           source = "status")
    @Mapping(target = "priority",         source = "priority")
    @Mapping(target = "slaDeadline",      source = "slaDeadline")
    @Mapping(target = "isSlaBreached",    source = "isSlaBreached")
    @Mapping(target = "reportedBy",       source = "reportedBy")
    @Mapping(target = "workflowRequestId", source = "workflowRequestId")
    @Mapping(target = "createdAt",        source = "createdAt")
    @Mapping(target = "updatedAt",        source = "updatedAt")
    IncidentResponse toIncidentResponse(IncidentEntity entity);

    // ========================================
    // IncidentEntity → IncidentSummaryResponse
    // ========================================

    /**
     * IncidentEntity を一覧表示用のサマリーレスポンスに変換する。
     */
    @Mapping(target = "id",           source = "id")
    @Mapping(target = "title",        source = "title")
    @Mapping(target = "status",       source = "status")
    @Mapping(target = "priority",     source = "priority")
    @Mapping(target = "slaDeadline",  source = "slaDeadline")
    @Mapping(target = "isSlaBreached", source = "isSlaBreached")
    @Mapping(target = "reportedBy",   source = "reportedBy")
    @Mapping(target = "createdAt",    source = "createdAt")
    IncidentSummaryResponse toIncidentSummaryResponse(IncidentEntity entity);

    // ========================================
    // IncidentCategoryEntity → IncidentCategoryResponse
    // ========================================

    /**
     * IncidentCategoryEntity をカテゴリレスポンスに変換する。
     * description / icon / color / sortOrder はエンティティに未定義のため null 固定。
     */
    @Mapping(target = "id",          source = "id")
    @Mapping(target = "scopeType",   source = "scopeType")
    @Mapping(target = "scopeId",     source = "scopeId")
    @Mapping(target = "name",        source = "name")
    @Mapping(target = "description", expression = "java((String) null)")
    @Mapping(target = "icon",        expression = "java((String) null)")
    @Mapping(target = "color",       expression = "java((String) null)")
    @Mapping(target = "slaHours",    source = "slaHours")
    @Mapping(target = "isActive",    source = "isActive")
    @Mapping(target = "sortOrder",   expression = "java((Integer) null)")
    @Mapping(target = "createdAt",   source = "createdAt")
    IncidentCategoryResponse toIncidentCategoryResponse(IncidentCategoryEntity entity);

    // ========================================
    // MaintenanceScheduleEntity → MaintenanceScheduleResponse
    // ========================================

    /**
     * MaintenanceScheduleEntity をスケジュールレスポンスに変換する。
     * title は name フィールドから取得する。
     * nextTriggerAt は nextExecutionDate の日付先頭時刻（atStartOfDay）に変換する。
     * lastTriggeredAt はエンティティに未定義のため null 固定。
     */
    @Mapping(target = "id",               source = "id")
    @Mapping(target = "title",            source = "name")
    @Mapping(target = "cronExpression",   source = "cronExpression")
    @Mapping(target = "isActive",         source = "isActive")
    @Mapping(target = "lastTriggeredAt",  expression = "java((java.time.LocalDateTime) null)")
    @Mapping(target = "nextTriggerAt",    expression = "java(entity.getNextExecutionDate() != null ? entity.getNextExecutionDate().atStartOfDay() : null)")
    @Mapping(target = "createdAt",        source = "createdAt")
    MaintenanceScheduleResponse toMaintenanceScheduleResponse(MaintenanceScheduleEntity entity);

}
