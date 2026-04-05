package com.mannschaft.app.forms.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormFieldType;
import com.mannschaft.app.forms.FormMapper;
import com.mannschaft.app.forms.FormStatus;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.dto.SubmissionValueRequest;
import com.mannschaft.app.forms.dto.UpdateFormSubmissionRequest;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フォーム提出サービス。提出のCRUD・ステータス遷移を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormSubmissionService {

    private final FormSubmissionRepository submissionRepository;
    private final FormSubmissionValueRepository valueRepository;
    private final FormTemplateService templateService;
    private final FormMapper formMapper;

    /**
     * テンプレートに紐付く提出一覧をページング取得する。
     *
     * @param templateId テンプレートID
     * @param status     ステータスフィルタ（null の場合は全件）
     * @param pageable   ページング情報
     * @return 提出レスポンスのページ
     */
    public Page<FormSubmissionResponse> listSubmissionsByTemplate(
            Long templateId, String status, Pageable pageable) {
        Page<FormSubmissionEntity> page;
        if (status != null) {
            com.mannschaft.app.forms.SubmissionStatus submissionStatus =
                    com.mannschaft.app.forms.SubmissionStatus.valueOf(status);
            page = submissionRepository.findByTemplateIdAndStatusOrderByCreatedAtDesc(
                    templateId, submissionStatus, pageable);
        } else {
            page = submissionRepository.findByTemplateIdOrderByCreatedAtDesc(templateId, pageable);
        }
        return page.map(entity -> {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(entity.getId());
            return formMapper.toSubmissionResponseWithValues(entity, values);
        });
    }

    /**
     * ユーザーの提出一覧をページング取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param pageable  ページング情報
     * @return 提出レスポンスのページ
     */
    public Page<FormSubmissionResponse> listMySubmissions(
            Long userId, String scopeType, Long scopeId, Pageable pageable) {
        Page<FormSubmissionEntity> page = submissionRepository
                .findBySubmittedByAndScopeTypeAndScopeIdOrderByCreatedAtDesc(
                        userId, scopeType, scopeId, pageable);
        return page.map(entity -> {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(entity.getId());
            return formMapper.toSubmissionResponseWithValues(entity, values);
        });
    }

    /**
     * 提出詳細を取得する。
     *
     * @param submissionId 提出ID
     * @return 提出レスポンス
     */
    public FormSubmissionResponse getSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);
        return formMapper.toSubmissionResponseWithValues(entity, values);
    }

    /**
     * 提出を作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    提出者ユーザーID
     * @param request   作成リクエスト
     * @return 作成された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse createSubmission(
            String scopeType, Long scopeId, Long userId, CreateFormSubmissionRequest request) {
        FormTemplateEntity template = templateService.getTemplateEntity(request.getTemplateId());

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.TEMPLATE_NOT_PUBLISHED);
        }

        if (template.isDeadlinePassed()) {
            throw new BusinessException(FormErrorCode.TEMPLATE_DEADLINE_PASSED);
        }

        if (template.getMaxSubmissionsPerUser() > 0) {
            long existingCount = submissionRepository.countByTemplateIdAndSubmittedBy(
                    request.getTemplateId(), userId);
            if (existingCount >= template.getMaxSubmissionsPerUser()) {
                throw new BusinessException(FormErrorCode.MAX_SUBMISSIONS_EXCEEDED);
            }
        }

        long userSubmissionCount = submissionRepository.countByTemplateIdAndSubmittedBy(
                request.getTemplateId(), userId);

        FormSubmissionEntity entity = FormSubmissionEntity.builder()
                .templateId(request.getTemplateId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .submittedBy(userId)
                .submissionCountForUser((int) userSubmissionCount + 1)
                .build();

        if (Boolean.TRUE.equals(request.getSubmitImmediately())) {
            entity.submit();
        }

        FormSubmissionEntity saved = submissionRepository.save(entity);

        List<FormSubmissionValueEntity> values = List.of();
        if (request.getValues() != null && !request.getValues().isEmpty()) {
            values = saveValues(saved.getId(), request.getValues());
        }

        if (Boolean.TRUE.equals(request.getSubmitImmediately())) {
            template.incrementSubmissionCount();
        }

        log.info("提出作成: templateId={}, submissionId={}, userId={}", request.getTemplateId(), saved.getId(), userId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を更新する。
     *
     * @param submissionId 提出ID
     * @param userId       ユーザーID
     * @param request      更新リクエスト
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse updateSubmission(
            Long submissionId, Long userId, UpdateFormSubmissionRequest request) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));

        if (!entity.isEditable()) {
            FormTemplateEntity template = templateService.getTemplateEntity(entity.getTemplateId());
            if (!Boolean.TRUE.equals(template.getAllowEditAfterSubmit())) {
                throw new BusinessException(FormErrorCode.EDIT_AFTER_SUBMIT_NOT_ALLOWED);
            }
        }

        if (Boolean.TRUE.equals(request.getSubmitImmediately()) && !entity.isSubmitted()) {
            entity.submit();
            FormTemplateEntity template = templateService.getTemplateEntity(entity.getTemplateId());
            template.incrementSubmissionCount();
        }

        FormSubmissionEntity saved = submissionRepository.save(entity);

        List<FormSubmissionValueEntity> values;
        if (request.getValues() != null) {
            valueRepository.deleteBySubmissionId(submissionId);
            values = saveValues(submissionId, request.getValues());
        } else {
            values = valueRepository.findBySubmissionId(submissionId);
        }

        log.info("提出更新: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を承認する。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse approveSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.approve();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出承認: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を却下する。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse rejectSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.reject();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出却下: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を差し戻す。
     *
     * @param submissionId 提出ID
     * @return 更新された提出レスポンス
     */
    @Transactional
    public FormSubmissionResponse returnSubmission(Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);

        if (!entity.isSubmitted()) {
            throw new BusinessException(FormErrorCode.INVALID_SUBMISSION_STATUS);
        }

        entity.returnSubmission();
        FormSubmissionEntity saved = submissionRepository.save(entity);
        List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(submissionId);

        log.info("提出差し戻し: submissionId={}", submissionId);
        return formMapper.toSubmissionResponseWithValues(saved, values);
    }

    /**
     * 提出を論理削除する。
     *
     * @param submissionId 提出ID
     * @param userId       ユーザーID
     */
    @Transactional
    public void deleteSubmission(Long submissionId, Long userId) {
        FormSubmissionEntity entity = submissionRepository.findByIdAndSubmittedBy(submissionId, userId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));
        entity.softDelete();
        submissionRepository.save(entity);
        log.info("提出削除: submissionId={}", submissionId);
    }

    /**
     * 提出を取得する。存在しない場合は例外をスローする。
     */
    private FormSubmissionEntity findSubmissionOrThrow(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * 提出値を一括保存する。
     */
    private List<FormSubmissionValueEntity> saveValues(
            Long submissionId, List<SubmissionValueRequest> values) {
        List<FormSubmissionValueEntity> entities = values.stream()
                .map(v -> FormSubmissionValueEntity.builder()
                        .submissionId(submissionId)
                        .fieldKey(v.getFieldKey())
                        .fieldType(FormFieldType.valueOf(v.getFieldType()))
                        .textValue(v.getTextValue())
                        .numberValue(v.getNumberValue())
                        .dateValue(v.getDateValue())
                        .fileKey(v.getFileKey())
                        .isAutoFilled(v.getIsAutoFilled() != null ? v.getIsAutoFilled() : false)
                        .build())
                .toList();
        return valueRepository.saveAll(entities);
    }
}
