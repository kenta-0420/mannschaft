package com.mannschaft.app.service.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.FieldType;
import com.mannschaft.app.service.ServiceRecordErrorCode;
import com.mannschaft.app.service.ServiceRecordMapper;
import com.mannschaft.app.service.dto.CreateFieldRequest;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.dto.FieldSortOrderRequest;
import com.mannschaft.app.service.dto.SettingsResponse;
import com.mannschaft.app.service.dto.SortOrderResponse;
import com.mannschaft.app.service.dto.UpdateFieldRequest;
import com.mannschaft.app.service.dto.UpdateSettingsRequest;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * カスタムフィールド定義・設定サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceRecordFieldService {

    private final ServiceRecordFieldRepository fieldRepository;
    private final ServiceRecordSettingsRepository settingsRepository;
    private final ServiceRecordMapper mapper;
    private final ObjectMapper objectMapper;

    private static final int MAX_ACTIVE_FIELDS = 20;

    // ==================== カスタムフィールド CRUD ====================

    /**
     * カスタムフィールド定義一覧を取得する。
     */
    public List<FieldResponse> listFields(Long teamId) {
        return fieldRepository.findByTeamIdOrderBySortOrder(teamId)
                .stream()
                .map(mapper::toFieldResponse)
                .collect(Collectors.toList());
    }

    /**
     * カスタムフィールドを作成する。
     */
    @Transactional
    public FieldResponse createField(Long teamId, CreateFieldRequest request) {
        long activeCount = fieldRepository.countByTeamIdAndIsActiveTrue(teamId);
        if (activeCount >= MAX_ACTIVE_FIELDS) {
            throw new BusinessException(ServiceRecordErrorCode.FIELD_LIMIT_EXCEEDED);
        }

        FieldType fieldType = FieldType.valueOf(request.getFieldType());
        String options = convertOptionsToJson(request.getOptions());

        ServiceRecordFieldEntity entity = ServiceRecordFieldEntity.builder()
                .teamId(teamId)
                .fieldName(request.getFieldName())
                .fieldType(fieldType)
                .description(request.getDescription())
                .options(options)
                .isRequired(request.getIsRequired() != null ? request.getIsRequired() : false)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        ServiceRecordFieldEntity saved = fieldRepository.save(entity);
        log.info("カスタムフィールド作成: teamId={}, fieldId={}", teamId, saved.getId());
        return mapper.toFieldResponse(saved);
    }

    /**
     * カスタムフィールドを更新する（再有効化含む）。
     */
    @Transactional
    public FieldResponse updateField(Long teamId, Long id, UpdateFieldRequest request) {
        ServiceRecordFieldEntity entity = fieldRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND));

        // 再有効化時の上限チェック
        if (Boolean.TRUE.equals(request.getIsActive()) && !Boolean.TRUE.equals(entity.getIsActive())) {
            long activeCount = fieldRepository.countByTeamIdAndIsActiveTrue(teamId);
            if (activeCount >= MAX_ACTIVE_FIELDS) {
                throw new BusinessException(ServiceRecordErrorCode.FIELD_LIMIT_EXCEEDED);
            }
        }

        FieldType fieldType = FieldType.valueOf(request.getFieldType());
        String options = convertOptionsToJson(request.getOptions());

        entity.update(
                request.getFieldName(),
                fieldType,
                request.getDescription(),
                options,
                request.getIsRequired() != null ? request.getIsRequired() : entity.getIsRequired(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder(),
                request.getIsActive() != null ? request.getIsActive() : entity.getIsActive());

        ServiceRecordFieldEntity saved = fieldRepository.save(entity);
        log.info("カスタムフィールド更新: fieldId={}", id);
        return mapper.toFieldResponse(saved);
    }

    /**
     * カスタムフィールドを無効化する。
     */
    @Transactional
    public void deactivateField(Long teamId, Long id) {
        ServiceRecordFieldEntity entity = fieldRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND));
        entity.deactivate();
        fieldRepository.save(entity);
        log.info("カスタムフィールド無効化: fieldId={}", id);
    }

    /**
     * カスタムフィールドの並び順を一括更新する。
     */
    @Transactional
    public SortOrderResponse updateSortOrder(Long teamId, FieldSortOrderRequest request) {
        Map<Long, ServiceRecordFieldEntity> fieldMap = fieldRepository.findByTeamIdOrderBySortOrder(teamId)
                .stream()
                .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

        int updated = 0;
        for (FieldSortOrderRequest.FieldOrderEntry entry : request.getFieldOrders()) {
            ServiceRecordFieldEntity field = fieldMap.get(entry.getFieldId());
            if (field == null) {
                throw new BusinessException(ServiceRecordErrorCode.FIELD_NOT_FOUND);
            }
            field.updateSortOrder(entry.getSortOrder());
            fieldRepository.save(field);
            updated++;
        }

        log.info("カスタムフィールド並び替え: teamId={}, count={}", teamId, updated);
        return SortOrderResponse.builder().updatedCount(updated).build();
    }

    // ==================== 設定 ====================

    /**
     * 機能設定を取得する。
     */
    public SettingsResponse getSettings(Long teamId) {
        ServiceRecordSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> createDefaultSettings(teamId));
        return mapper.toSettingsResponse(settings);
    }

    /**
     * 機能設定を更新する。
     */
    @Transactional
    public SettingsResponse updateSettings(Long teamId, UpdateSettingsRequest request) {
        ServiceRecordSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> createDefaultSettings(teamId));

        settings.update(
                request.getIsDashboardEnabled() != null ? request.getIsDashboardEnabled() : settings.getIsDashboardEnabled(),
                request.getIsReactionEnabled() != null ? request.getIsReactionEnabled() : settings.getIsReactionEnabled());

        ServiceRecordSettingsEntity saved = settingsRepository.save(settings);
        log.info("機能設定更新: teamId={}", teamId);
        return mapper.toSettingsResponse(saved);
    }

    // ==================== プライベートメソッド ====================

    private ServiceRecordSettingsEntity createDefaultSettings(Long teamId) {
        ServiceRecordSettingsEntity settings = ServiceRecordSettingsEntity.builder()
                .teamId(teamId)
                .build();
        return settingsRepository.save(settings);
    }

    private String convertOptionsToJson(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ServiceRecordErrorCode.VALIDATION_ERROR);
        }
    }
}
