package com.mannschaft.app.service;

import com.mannschaft.app.service.dto.AttachmentResponse;
import com.mannschaft.app.service.dto.CustomFieldValueResponse;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.dto.SettingsResponse;
import com.mannschaft.app.service.dto.TemplateFieldValueResponse;
import com.mannschaft.app.service.dto.TemplateResponse;
import com.mannschaft.app.service.entity.ServiceRecordAttachmentEntity;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.entity.ServiceRecordTemplateEntity;
import com.mannschaft.app.service.entity.ServiceRecordTemplateValueEntity;
import com.mannschaft.app.service.entity.ServiceRecordValueEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

/**
 * サービス履歴機能のマッパー。
 */
@Mapper(componentModel = "spring")
public interface ServiceRecordMapper {

    @Mapping(target = "fieldType", source = "fieldType", qualifiedByName = "fieldTypeToString")
    @Mapping(target = "options", source = "options", qualifiedByName = "jsonToStringList")
    FieldResponse toFieldResponse(ServiceRecordFieldEntity entity);

    @Mapping(target = "teamId", source = "entity.teamId")
    @Mapping(target = "isDashboardEnabled", source = "entity.isDashboardEnabled")
    @Mapping(target = "isReactionEnabled", source = "entity.isReactionEnabled")
    SettingsResponse toSettingsResponse(ServiceRecordSettingsEntity entity);

    @Mapping(target = "downloadUrl", ignore = true)
    AttachmentResponse toAttachmentResponse(ServiceRecordAttachmentEntity entity);

    @Named("fieldTypeToString")
    default String fieldTypeToString(FieldType fieldType) {
        return fieldType != null ? fieldType.name() : null;
    }

    @Named("jsonToStringList")
    default List<String> jsonToStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    default CustomFieldValueResponse toCustomFieldValueResponse(ServiceRecordValueEntity value,
                                                                  ServiceRecordFieldEntity field) {
        return CustomFieldValueResponse.builder()
                .fieldId(field.getId())
                .fieldName(field.getFieldName())
                .fieldType(field.getFieldType().name())
                .value(value.getValue())
                .build();
    }

    default TemplateResponse toTemplateResponse(ServiceRecordTemplateEntity entity,
                                                 List<TemplateFieldValueResponse> fieldValues) {
        String scope = entity.getTeamId() != null ? "TEAM" : "ORGANIZATION";
        return TemplateResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .titleTemplate(entity.getTitleTemplate())
                .noteTemplate(entity.getNoteTemplate())
                .defaultDurationMinutes(entity.getDefaultDurationMinutes())
                .sortOrder(entity.getSortOrder())
                .scope(scope)
                .teamId(entity.getTeamId())
                .organizationId(entity.getOrganizationId())
                .customFieldValues(fieldValues)
                .build();
    }

    default TemplateFieldValueResponse toTemplateFieldValueResponse(ServiceRecordTemplateValueEntity value,
                                                                      ServiceRecordFieldEntity field) {
        return TemplateFieldValueResponse.builder()
                .fieldId(field.getId())
                .fieldName(field.getFieldName())
                .defaultValue(value.getDefaultValue())
                .build();
    }
}
