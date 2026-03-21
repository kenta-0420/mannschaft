package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.FieldScope;
import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.ImportTemplateRequest;
import com.mannschaft.app.activity.dto.UpdateTemplateRequest;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 活動テンプレートサービス。テンプレートのCRUD・共有・インポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityTemplateService {

    private final ActivityTemplateRepository templateRepository;
    private final ActivityTemplateFieldRepository fieldRepository;
    private final ActivityMapper activityMapper;

    /**
     * テンプレート一覧を取得する。
     */
    public List<ActivityTemplateResponse> listTemplates(Long teamId, Long organizationId) {
        List<ActivityTemplateEntity> templates;
        if (teamId != null) {
            templates = templateRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        } else {
            templates = templateRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        }
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
     * 公式テンプレート一覧を取得する。
     */
    public List<ActivityTemplateResponse> listOfficialTemplates() {
        List<ActivityTemplateEntity> templates = templateRepository.findByScopeTypeAndIsOfficialTrue("SYSTEM");
        return templates.stream().map(this::toResponseWithFields).collect(Collectors.toList());
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public ActivityTemplateResponse createTemplate(Long userId, CreateTemplateRequest request) {
        // 上限チェック
        long count = request.getTeamId() != null
                ? templateRepository.countByTeamId(request.getTeamId())
                : templateRepository.countByOrganizationId(request.getOrganizationId());
        if (count >= 10) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        // フィールド数チェック
        if (request.getFields() != null && request.getFields().size() > 20) {
            throw new BusinessException(ActivityErrorCode.FIELD_LIMIT_EXCEEDED);
        }

        ActivityScopeType scopeType = request.getTeamId() != null
                ? ActivityScopeType.TEAM : ActivityScopeType.ORGANIZATION;
        ActivityVisibility visibility = request.getDefaultVisibility() != null
                ? ActivityVisibility.valueOf(request.getDefaultVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        ActivityTemplateEntity entity = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .defaultTitlePattern(request.getDefaultTitlePattern())
                .defaultVisibility(visibility)
                .defaultLocation(request.getDefaultLocation())
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(entity);

        // フィールド作成
        if (request.getFields() != null) {
            int order = 0;
            for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(saved.getId())
                        .scope(field.getScope() != null ? FieldScope.valueOf(field.getScope()) : FieldScope.ACTIVITY)
                        .fieldName(field.getFieldName())
                        .fieldType(FieldType.valueOf(field.getFieldType()))
                        .options(field.getOptions())
                        .unit(field.getUnit())
                        .isRequired(field.getIsRequired() != null && field.getIsRequired())
                        .defaultValue(field.getDefaultValue())
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
                request.getColor(), request.getDefaultTitlePattern(), visibility,
                request.getDefaultLocation());

        // フィールドの更新（全量入れ替え）
        if (request.getFields() != null) {
            if (request.getFields().size() > 20) {
                throw new BusinessException(ActivityErrorCode.FIELD_LIMIT_EXCEEDED);
            }
            fieldRepository.deleteByTemplateId(id);
            int order = 0;
            for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(id)
                        .scope(field.getScope() != null ? FieldScope.valueOf(field.getScope()) : FieldScope.ACTIVITY)
                        .fieldName(field.getFieldName())
                        .fieldType(FieldType.valueOf(field.getFieldType()))
                        .options(field.getOptions())
                        .unit(field.getUnit())
                        .isRequired(field.getIsRequired() != null && field.getIsRequired())
                        .defaultValue(field.getDefaultValue())
                        .sortOrder(field.getSortOrder() != null ? field.getSortOrder() : order++)
                        .build();
                fieldRepository.save(fieldEntity);
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
     * テンプレートの共有を有効化する。
     */
    @Transactional
    public ActivityTemplateResponse enableShare(Long id) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        String shareCode = UUID.randomUUID().toString().substring(0, 8);
        entity.enableShare(shareCode);
        ActivityTemplateEntity saved = templateRepository.save(entity);
        log.info("テンプレート共有有効化: templateId={}, shareCode={}", id, shareCode);
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートの共有を無効化する。
     */
    @Transactional
    public void disableShare(Long id) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        entity.disableShare();
        templateRepository.save(entity);
        log.info("テンプレート共有無効化: templateId={}", id);
    }

    /**
     * テンプレートをインポートする。
     */
    @Transactional
    public ActivityTemplateResponse importTemplate(Long userId, ImportTemplateRequest request) {
        ActivityTemplateEntity source;
        if (request.getSourceTemplateId() != null) {
            source = findTemplateOrThrow(request.getSourceTemplateId());
        } else if (request.getShareCode() != null) {
            source = templateRepository.findByShareCode(request.getShareCode())
                    .orElseThrow(() -> new BusinessException(ActivityErrorCode.SHARE_CODE_NOT_FOUND));
        } else {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND);
        }

        // 上限チェック
        long count = request.getTeamId() != null
                ? templateRepository.countByTeamId(request.getTeamId())
                : templateRepository.countByOrganizationId(request.getOrganizationId());
        if (count >= 10) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ActivityScopeType scopeType = request.getTeamId() != null
                ? ActivityScopeType.TEAM : ActivityScopeType.ORGANIZATION;

        ActivityTemplateEntity newTemplate = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .name(source.getName())
                .description(source.getDescription())
                .icon(source.getIcon())
                .color(source.getColor())
                .defaultTitlePattern(source.getDefaultTitlePattern())
                .defaultVisibility(source.getDefaultVisibility())
                .defaultLocation(source.getDefaultLocation())
                .sourceTemplateId(source.getId())
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(newTemplate);

        // フィールドのコピー
        List<ActivityTemplateFieldEntity> sourceFields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(source.getId());
        for (ActivityTemplateFieldEntity field : sourceFields) {
            ActivityTemplateFieldEntity copy = ActivityTemplateFieldEntity.builder()
                    .templateId(saved.getId())
                    .scope(field.getScope())
                    .fieldName(field.getFieldName())
                    .fieldType(field.getFieldType())
                    .options(field.getOptions())
                    .unit(field.getUnit())
                    .isRequired(field.getIsRequired())
                    .defaultValue(field.getDefaultValue())
                    .sortOrder(field.getSortOrder())
                    .build();
            fieldRepository.save(copy);
        }

        // インポート元のカウント更新
        source.incrementImportCount();
        templateRepository.save(source);

        log.info("テンプレートインポート: sourceId={}, newId={}", source.getId(), saved.getId());
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
        List<ActivityTemplateFieldEntity> fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId());
        List<ActivityTemplateResponse.TemplateFieldResponse> fieldResponses = activityMapper.toTemplateFieldResponseList(fields);
        return new ActivityTemplateResponse(
                entity.getId(), entity.getScopeType().name(),
                entity.getTeamId(), entity.getOrganizationId(),
                entity.getName(), entity.getDescription(),
                entity.getIcon(), entity.getColor(),
                entity.getDefaultTitlePattern(), entity.getDefaultVisibility().name(),
                entity.getDefaultLocation(), entity.getShareCode(),
                entity.getIsShared(), entity.getIsOfficial(),
                entity.getUseCount(), entity.getImportCount(),
                fieldResponses, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
