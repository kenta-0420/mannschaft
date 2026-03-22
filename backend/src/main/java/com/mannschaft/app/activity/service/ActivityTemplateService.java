package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.DuplicateTemplateRequest;
import com.mannschaft.app.activity.dto.ImportTemplateRequest;
import com.mannschaft.app.activity.dto.UpdateTemplateRequest;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.activity.repository.SystemActivityTemplatePresetRepository;
import com.mannschaft.app.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 活動テンプレートサービス。テンプレートのCRUD・複製・インポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityTemplateService {

    private static final int MAX_TEMPLATES_PER_SCOPE = 20;
    private static final int MAX_FIELDS_PER_TEMPLATE = 15;

    private final ActivityTemplateRepository templateRepository;
    private final ActivityTemplateFieldRepository fieldRepository;
    private final SystemActivityTemplatePresetRepository presetRepository;
    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;

    /**
     * テンプレート一覧を取得する。
     */
    public List<ActivityTemplateResponse> listTemplates(ActivityScopeType scopeType, Long scopeId) {
        List<ActivityTemplateEntity> templates =
                templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(scopeType, scopeId);
        return templates.stream().map(this::toResponseWithFields).collect(Collectors.toList());
    }

    /**
     * テンプレート詳細を取得する。
     */
    public ActivityTemplateResponse getTemplate(Long id) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        return toResponseWithFields(entity);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public ActivityTemplateResponse createTemplate(Long userId, ActivityScopeType scopeType,
                                                    Long scopeId, CreateTemplateRequest request) {
        // 上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        // フィールド数チェック
        if (request.getFields() != null && request.getFields().size() > MAX_FIELDS_PER_TEMPLATE) {
            throw new BusinessException(ActivityErrorCode.FIELD_LIMIT_EXCEEDED);
        }

        ActivityVisibility visibility = request.getDefaultVisibility() != null
                ? ActivityVisibility.valueOf(request.getDefaultVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        ActivityTemplateEntity entity = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .isParticipantRequired(request.getIsParticipantRequired() != null
                        ? request.getIsParticipantRequired() : true)
                .defaultVisibility(visibility)
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(entity);

        // フィールド作成
        if (request.getFields() != null) {
            int order = 0;
            for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(saved.getId())
                        .fieldKey(field.getFieldKey())
                        .fieldLabel(field.getFieldLabel())
                        .fieldType(FieldType.valueOf(field.getFieldType()))
                        .isRequired(field.getIsRequired() != null && field.getIsRequired())
                        .optionsJson(field.getOptionsJson())
                        .placeholder(field.getPlaceholder())
                        .unit(field.getUnit())
                        .isAggregatable(field.getIsAggregatable() != null && field.getIsAggregatable())
                        .sortOrder(field.getSortOrder() != null ? field.getSortOrder() : order++)
                        .build();
                fieldRepository.save(fieldEntity);
            }
        }

        log.info("テンプレート作成: templateId={}, name={}", saved.getId(), saved.getName());
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public ActivityTemplateResponse updateTemplate(Long id, UpdateTemplateRequest request) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        ActivityVisibility visibility = request.getDefaultVisibility() != null
                ? ActivityVisibility.valueOf(request.getDefaultVisibility()) : entity.getDefaultVisibility();

        entity.update(request.getName(), request.getDescription(), request.getIcon(),
                request.getColor(),
                request.getIsParticipantRequired() != null ? request.getIsParticipantRequired() : entity.getIsParticipantRequired(),
                visibility, entity.getSortOrder());

        // フィールドの更新（field_key ベースの差分更新）
        if (request.getFields() != null) {
            if (request.getFields().size() > MAX_FIELDS_PER_TEMPLATE) {
                throw new BusinessException(ActivityErrorCode.FIELD_LIMIT_EXCEEDED);
            }

            List<ActivityTemplateFieldEntity> existingFields =
                    fieldRepository.findByTemplateIdOrderBySortOrderAsc(id);
            Map<String, ActivityTemplateFieldEntity> existingByKey = existingFields.stream()
                    .collect(Collectors.toMap(ActivityTemplateFieldEntity::getFieldKey, Function.identity()));

            // リクエストの field_key を収集
            Map<String, CreateTemplateRequest.TemplateFieldInput> requestByKey = request.getFields().stream()
                    .collect(Collectors.toMap(CreateTemplateRequest.TemplateFieldInput::getFieldKey, Function.identity()));

            // 既存にあってリクエストにない → 削除
            for (ActivityTemplateFieldEntity existing : existingFields) {
                if (!requestByKey.containsKey(existing.getFieldKey())) {
                    fieldRepository.delete(existing);
                }
            }

            int order = 0;
            for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
                ActivityTemplateFieldEntity existing = existingByKey.get(field.getFieldKey());
                if (existing != null) {
                    // field_type 変更チェック
                    if (!existing.getFieldType().name().equals(field.getFieldType())) {
                        throw new BusinessException(ActivityErrorCode.FIELD_TYPE_CHANGE_NOT_ALLOWED);
                    }
                    // 更新
                    existing.update(
                            field.getFieldLabel(),
                            field.getIsRequired() != null ? field.getIsRequired() : existing.getIsRequired(),
                            field.getOptionsJson(),
                            field.getPlaceholder(),
                            field.getUnit(),
                            field.getIsAggregatable() != null ? field.getIsAggregatable() : existing.getIsAggregatable(),
                            field.getSortOrder() != null ? field.getSortOrder() : order++
                    );
                    fieldRepository.save(existing);
                } else {
                    // 新規追加
                    ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                            .templateId(id)
                            .fieldKey(field.getFieldKey())
                            .fieldLabel(field.getFieldLabel())
                            .fieldType(FieldType.valueOf(field.getFieldType()))
                            .isRequired(field.getIsRequired() != null && field.getIsRequired())
                            .optionsJson(field.getOptionsJson())
                            .placeholder(field.getPlaceholder())
                            .unit(field.getUnit())
                            .isAggregatable(field.getIsAggregatable() != null && field.getIsAggregatable())
                            .sortOrder(field.getSortOrder() != null ? field.getSortOrder() : order++)
                            .build();
                    fieldRepository.save(fieldEntity);
                }
            }
        }

        ActivityTemplateEntity saved = templateRepository.save(entity);
        log.info("テンプレート更新: templateId={}", id);
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートを論理削除する。
     */
    @Transactional
    public void deleteTemplate(Long id) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        entity.softDelete();
        templateRepository.save(entity);
        log.info("テンプレート削除: templateId={}", id);
    }

    /**
     * テンプレートを別スコープにコピーする。
     */
    @Transactional
    public ActivityTemplateResponse duplicateTemplate(Long id, Long userId, DuplicateTemplateRequest request) {
        ActivityTemplateEntity source = findTemplateOrThrow(id);
        ActivityScopeType targetScopeType = ActivityScopeType.valueOf(request.getTargetScopeType());
        Long targetScopeId = request.getTargetScopeId();

        // コピー先の上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(targetScopeType, targetScopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ActivityTemplateEntity newTemplate = ActivityTemplateEntity.builder()
                .scopeType(targetScopeType)
                .scopeId(targetScopeId)
                .name(source.getName() + "（コピー）")
                .description(source.getDescription())
                .icon(source.getIcon())
                .color(source.getColor())
                .isParticipantRequired(source.getIsParticipantRequired())
                .defaultVisibility(source.getDefaultVisibility())
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(newTemplate);

        // フィールドのコピー
        List<ActivityTemplateFieldEntity> sourceFields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(source.getId());
        for (ActivityTemplateFieldEntity field : sourceFields) {
            ActivityTemplateFieldEntity copy = ActivityTemplateFieldEntity.builder()
                    .templateId(saved.getId())
                    .fieldKey(field.getFieldKey())
                    .fieldLabel(field.getFieldLabel())
                    .fieldType(field.getFieldType())
                    .isRequired(field.getIsRequired())
                    .optionsJson(field.getOptionsJson())
                    .placeholder(field.getPlaceholder())
                    .unit(field.getUnit())
                    .isAggregatable(field.getIsAggregatable())
                    .sortOrder(field.getSortOrder())
                    .build();
            fieldRepository.save(copy);
        }

        log.info("テンプレート複製: sourceId={}, newId={}, targetScope={}:{}", id, saved.getId(),
                targetScopeType, targetScopeId);
        return toResponseWithFields(saved);
    }

    /**
     * プリセットテンプレートをスコープにインポートする。
     */
    @Transactional
    public ActivityTemplateResponse importPreset(Long userId, ImportTemplateRequest request) {
        SystemActivityTemplatePresetEntity preset = presetRepository.findById(request.getPresetId())
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.PRESET_NOT_FOUND));

        ActivityScopeType scopeType = ActivityScopeType.valueOf(request.getScopeType());
        Long scopeId = request.getScopeId();

        // 上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ActivityVisibility visibility = ActivityVisibility.valueOf(preset.getDefaultVisibility());

        ActivityTemplateEntity newTemplate = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(preset.getName())
                .description(preset.getDescription())
                .icon(preset.getIcon())
                .color(preset.getColor())
                .isParticipantRequired(preset.getIsParticipantRequired())
                .defaultVisibility(visibility)
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(newTemplate);

        // fields_json を展開して activity_template_fields に INSERT
        try {
            List<Map<String, Object>> fields = objectMapper.readValue(
                    preset.getFieldsJson(), new TypeReference<>() {});
            int order = 0;
            for (Map<String, Object> field : fields) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(saved.getId())
                        .fieldKey((String) field.get("field_key"))
                        .fieldLabel((String) field.get("field_label"))
                        .fieldType(FieldType.valueOf((String) field.get("field_type")))
                        .isRequired(field.get("is_required") != null && (Boolean) field.get("is_required"))
                        .optionsJson(field.get("options_json") != null
                                ? objectMapper.writeValueAsString(field.get("options_json")) : null)
                        .placeholder((String) field.get("placeholder"))
                        .unit((String) field.get("unit"))
                        .isAggregatable(field.get("is_aggregatable") != null && (Boolean) field.get("is_aggregatable"))
                        .sortOrder(field.get("sort_order") != null
                                ? ((Number) field.get("sort_order")).intValue() : order++)
                        .build();
                fieldRepository.save(fieldEntity);
            }
        } catch (Exception e) {
            throw new RuntimeException("プリセットのfields_jsonのパースに失敗しました", e);
        }

        log.info("プリセットインポート: presetId={}, newTemplateId={}", preset.getId(), saved.getId());
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートエンティティを取得する。存在しない場合は例外をスローする。
     */
    ActivityTemplateEntity findTemplateOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * テンプレートレスポンスにフィールド定義を付与して返す。
     */
    private ActivityTemplateResponse toResponseWithFields(ActivityTemplateEntity entity) {
        List<ActivityTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId());
        List<ActivityTemplateResponse.TemplateFieldResponse> fieldResponses =
                activityMapper.toTemplateFieldResponseList(fields);
        return new ActivityTemplateResponse(
                entity.getId(), entity.getScopeType().name(),
                entity.getScopeId(),
                entity.getName(), entity.getDescription(),
                entity.getIcon(), entity.getColor(),
                entity.getIsParticipantRequired(),
                entity.getDefaultVisibility().name(),
                entity.getSortOrder(),
                fieldResponses, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
