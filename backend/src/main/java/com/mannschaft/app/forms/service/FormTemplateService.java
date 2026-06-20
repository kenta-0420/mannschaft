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

        // managed entity を直接ミューテートして主キー・@Version を保持する（toBuilder().build() は id 欠落で INSERT 化＝行重複を招くため使用しない）
        entity.applyUpdate(
                request.getName(),
                request.getDescription(),
                request.getIcon(),
                request.getColor(),
                request.getRequiresApproval(),
                request.getWorkflowTemplateId(),
                request.getIsSealOnPdf(),
                request.getDeadline(),
                request.getAllowEditAfterSubmit(),
                request.getAutoFillEnabled(),
                request.getMaxSubmissionsPerUser(),
                request.getSortOrder(),
                request.getTargetCount());

        FormTemplateEntity saved = templateRepository.save(entity);

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
     * F05.7 Phase 11 第四陣 4-B: テンプレートを複製する。
     *
     * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/duplicate} 用。
     * 名称末尾に「(コピー)」を付与した DRAFT 状態の新規テンプレートを生成する。
     * フィールド定義もすべて複製する（id / sort_order は新規採番）。</p>
     *
     * <p>制約:</p>
     * <ul>
     *   <li>複製先は同一スコープ。クロススコープ複製はサポート外（Phase 5 以降の検討事項）</li>
     *   <li>新規テンプレートは {@code status = DRAFT} で作成、submissionCount / publishedAt / closedAt はリセット</li>
     *   <li>workflowTemplateId / presetId はそのまま継承（参照整合性はアプリ層で別途検証）</li>
     * </ul>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープ ID
     * @param templateId 複製元テンプレート ID
     * @param userId     実行ユーザー ID（複製先の createdBy になる）
     * @return 複製されたテンプレートのレスポンス
     */
    @Transactional
    public FormTemplateResponse duplicateTemplate(
            String scopeType, Long scopeId, Long templateId, Long userId) {
        FormTemplateEntity original = findTemplateOrThrow(scopeType, scopeId, templateId);
        List<FormTemplateFieldEntity> originalFields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);

        FormTemplateEntity copy = FormTemplateEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .name(original.getName() + " (コピー)")
                .description(original.getDescription())
                .icon(original.getIcon())
                .color(original.getColor())
                .requiresApproval(original.getRequiresApproval())
                .workflowTemplateId(original.getWorkflowTemplateId())
                .isSealOnPdf(original.getIsSealOnPdf())
                .deadline(original.getDeadline())
                .allowEditAfterSubmit(original.getAllowEditAfterSubmit())
                .autoFillEnabled(original.getAutoFillEnabled())
                .maxSubmissionsPerUser(original.getMaxSubmissionsPerUser())
                .sortOrder(original.getSortOrder())
                .presetId(original.getPresetId())
                .targetCount(0)
                .createdBy(userId)
                .build();

        FormTemplateEntity savedTemplate = templateRepository.save(copy);

        List<FormTemplateFieldEntity> copiedFields = originalFields.stream()
                .map(f -> (FormTemplateFieldEntity) FormTemplateFieldEntity.builder()
                        .templateId(savedTemplate.getId())
                        .fieldKey(f.getFieldKey())
                        .fieldLabel(f.getFieldLabel())
                        .fieldType(f.getFieldType())
                        .isRequired(f.getIsRequired())
                        .sortOrder(f.getSortOrder())
                        .autoFillKey(f.getAutoFillKey())
                        .optionsJson(f.getOptionsJson())
                        .placeholder(f.getPlaceholder())
                        .build())
                .toList();
        List<FormTemplateFieldEntity> savedFields = fieldRepository.saveAll(copiedFields);

        log.info("テンプレート複製: originalId={}, copyId={}, userId={}",
                templateId, savedTemplate.getId(), userId);
        return formMapper.toTemplateResponseWithFields(savedTemplate, savedFields);
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
                .map(f -> (FormTemplateFieldEntity) FormTemplateFieldEntity.builder()
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
