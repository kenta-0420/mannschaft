package com.mannschaft.app.forms.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormFieldType;
import com.mannschaft.app.forms.FormMapper;
import com.mannschaft.app.forms.FormStatus;
import com.mannschaft.app.forms.dto.CreateFormTemplateRequest;
import com.mannschaft.app.forms.dto.FormFieldRequest;
import com.mannschaft.app.forms.dto.FormTemplateResponse;
import com.mannschaft.app.forms.dto.UpdateFormTemplateRequest;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * フォームテンプレートサービス。テンプレートのCRUD・ステータス遷移を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormTemplateService {

    private final FormTemplateRepository templateRepository;
    private final FormTemplateFieldRepository fieldRepository;
    private final FormMapper formMapper;

    /**
     * テンプレート一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return テンプレートレスポンスのページ
     */
    public Page<FormTemplateResponse> listTemplates(
            String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<FormTemplateEntity> page;
        if (status != null) {
            FormStatus formStatus = FormStatus.valueOf(status);
            page = templateRepository.findByScopeTypeAndScopeIdAndStatusOrderBySortOrderAsc(
                    scopeType, scopeId, formStatus, pageable);
        } else {
            page = templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(
                    scopeType, scopeId, pageable);
        }
        return page.map(entity -> {
            List<FormTemplateFieldEntity> fields =
                    fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId());
            return formMapper.toTemplateResponseWithFields(entity, fields);
        });
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param templateId テンプレートID
     * @return テンプレートレスポンス
     */
    public FormTemplateResponse getTemplate(String scopeType, Long scopeId, Long templateId) {
        FormTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        List<FormTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        return formMapper.toTemplateResponseWithFields(entity, fields);
    }

    /**
     * テンプレートを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ユーザーID
     * @param request   作成リクエスト
     * @return 作成されたテンプレートレスポンス
     */
    @Transactional
    public FormTemplateResponse createTemplate(
            String scopeType, Long scopeId, Long userId, CreateFormTemplateRequest request) {
        FormTemplateEntity entity = FormTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .requiresApproval(request.getRequiresApproval() != null ? request.getRequiresApproval() : false)
                .workflowTemplateId(request.getWorkflowTemplateId())
                .isSealOnPdf(request.getIsSealOnPdf() != null ? request.getIsSealOnPdf() : false)
                .deadline(request.getDeadline())
                .allowEditAfterSubmit(request.getAllowEditAfterSubmit() != null ? request.getAllowEditAfterSubmit() : false)
                .autoFillEnabled(request.getAutoFillEnabled() != null ? request.getAutoFillEnabled() : false)
                .maxSubmissionsPerUser(request.getMaxSubmissionsPerUser() != null ? request.getMaxSubmissionsPerUser() : 0)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .presetId(request.getPresetId())
                .targetCount(request.getTargetCount() != null ? request.getTargetCount() : 0)
                .createdBy(userId)
                .build();

        FormTemplateEntity saved = templateRepository.save(entity);

        List<FormTemplateFieldEntity> fields = List.of();
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            validateFieldKeys(request.getFields());
            fields = saveFields(saved.getId(), request.getFields());
        }

        log.info("テンプレート作成: scopeType={}, scopeId={}, templateId={}", scopeType, scopeId, saved.getId());
        return formMapper.toTemplateResponseWithFields(saved, fields);
    }

    /**
     * テンプレートを更新する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param templateId テンプレートID
     * @param request    更新リクエスト
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public FormTemplateResponse updateTemplate(
            String scopeType, Long scopeId, Long templateId, UpdateFormTemplateRequest request) {
        FormTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);

        FormTemplateEntity updated = entity.toBuilder()
                .name(request.getName() != null ? request.getName() : entity.getName())
                .description(request.getDescription() != null ? request.getDescription() : entity.getDescription())
                .icon(request.getIcon() != null ? request.getIcon() : entity.getIcon())
                .color(request.getColor() != null ? request.getColor() : entity.getColor())
                .requiresApproval(request.getRequiresApproval() != null ? request.getRequiresApproval() : entity.getRequiresApproval())
                .workflowTemplateId(request.getWorkflowTemplateId() != null ? request.getWorkflowTemplateId() : entity.getWorkflowTemplateId())
                .isSealOnPdf(request.getIsSealOnPdf() != null ? request.getIsSealOnPdf() : entity.getIsSealOnPdf())
                .deadline(request.getDeadline() != null ? request.getDeadline() : entity.getDeadline())
                .allowEditAfterSubmit(request.getAllowEditAfterSubmit() != null ? request.getAllowEditAfterSubmit() : entity.getAllowEditAfterSubmit())
                .autoFillEnabled(request.getAutoFillEnabled() != null ? request.getAutoFillEnabled() : entity.getAutoFillEnabled())
                .maxSubmissionsPerUser(request.getMaxSubmissionsPerUser() != null ? request.getMaxSubmissionsPerUser() : entity.getMaxSubmissionsPerUser())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder())
                .targetCount(request.getTargetCount() != null ? request.getTargetCount() : entity.getTargetCount())
                .build();

        FormTemplateEntity saved = templateRepository.save(updated);

        List<FormTemplateFieldEntity> fields;
        if (request.getFields() != null) {
            validateFieldKeys(request.getFields());
            fieldRepository.deleteByTemplateId(templateId);
            fields = saveFields(templateId, request.getFields());
        } else {
            fields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        }

        log.info("テンプレート更新: templateId={}", templateId);
        return formMapper.toTemplateResponseWithFields(saved, fields);
    }

    /**
     * テンプレートを公開する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param templateId テンプレートID
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public FormTemplateResponse publishTemplate(String scopeType, Long scopeId, Long templateId) {
        FormTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);

        if (!entity.isPublishable()) {
            throw new BusinessException(FormErrorCode.INVALID_TEMPLATE_STATUS);
        }

        long fieldCount = fieldRepository.countByTemplateId(templateId);
        if (fieldCount == 0) {
            throw new BusinessException(FormErrorCode.EMPTY_FIELDS);
        }

        entity.publish();
        FormTemplateEntity saved = templateRepository.save(entity);
        List<FormTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);

        log.info("テンプレート公開: templateId={}", templateId);
        return formMapper.toTemplateResponseWithFields(saved, fields);
    }

    /**
     * テンプレートを閉鎖する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param templateId テンプレートID
     * @return 更新されたテンプレートレスポンス
     */
    @Transactional
    public FormTemplateResponse closeTemplate(String scopeType, Long scopeId, Long templateId) {
        FormTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);

        if (!entity.isClosable()) {
            throw new BusinessException(FormErrorCode.INVALID_TEMPLATE_STATUS);
        }

        entity.close();
        FormTemplateEntity saved = templateRepository.save(entity);
        List<FormTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);

        log.info("テンプレート閉鎖: templateId={}", templateId);
        return formMapper.toTemplateResponseWithFields(saved, fields);
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param templateId テンプレートID
     */
    @Transactional
    public void deleteTemplate(String scopeType, Long scopeId, Long templateId) {
        FormTemplateEntity entity = findTemplateOrThrow(scopeType, scopeId, templateId);
        entity.softDelete();
        templateRepository.save(entity);
        log.info("テンプレート削除: templateId={}", templateId);
    }

    /**
     * テンプレートエンティティを取得する（他サービスからの参照用）。
     *
     * @param templateId テンプレートID
     * @return テンプレートエンティティ
     */
    public FormTemplateEntity getTemplateEntity(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * テンプレートを取得する。存在しない場合は例外をスローする。
     */
    private FormTemplateEntity findTemplateOrThrow(String scopeType, Long scopeId, Long templateId) {
        return templateRepository.findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * フィールドキーの重複を検証する。
     */
    private void validateFieldKeys(List<FormFieldRequest> fields) {
        Set<String> keys = new HashSet<>();
        for (FormFieldRequest field : fields) {
            if (!keys.add(field.getFieldKey())) {
                throw new BusinessException(FormErrorCode.DUPLICATE_FIELD_KEY);
            }
        }
    }

    /**
     * フィールドを一括保存する。
     */
    private List<FormTemplateFieldEntity> saveFields(Long templateId, List<FormFieldRequest> fields) {
        List<FormTemplateFieldEntity> entities = fields.stream()
                .map(f -> FormTemplateFieldEntity.builder()
                        .templateId(templateId)
                        .fieldKey(f.getFieldKey())
                        .fieldLabel(f.getFieldLabel())
                        .fieldType(FormFieldType.valueOf(f.getFieldType()))
                        .isRequired(f.getIsRequired() != null ? f.getIsRequired() : false)
                        .sortOrder(f.getSortOrder() != null ? f.getSortOrder() : 0)
                        .autoFillKey(f.getAutoFillKey())
                        .optionsJson(f.getOptionsJson())
                        .placeholder(f.getPlaceholder())
                        .build())
                .toList();
        return fieldRepository.saveAll(entities);
    }
}
