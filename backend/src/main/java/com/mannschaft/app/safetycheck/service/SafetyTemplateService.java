package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.dto.CreateTemplateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.dto.UpdateTemplateRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 安否確認テンプレートサービス。テンプレートのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyTemplateService {

    private final SafetyCheckTemplateRepository templateRepository;
    private final SafetyCheckMapper mapper;

    /**
     * 利用可能なテンプレート一覧を取得する（スコープ別 + システムデフォルト）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return テンプレート一覧
     */
    public List<SafetyTemplateResponse> listTemplates(String scopeType, Long scopeId) {
        SafetyCheckScopeType scope = SafetyCheckScopeType.valueOf(scopeType);
        List<SafetyCheckTemplateEntity> entities = templateRepository.findAvailableTemplates(scope, scopeId);
        return mapper.toTemplateResponseList(entities);
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param templateId テンプレートID
     * @return テンプレート詳細
     */
    public SafetyTemplateResponse getTemplate(Long templateId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを作成する。
     *
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse createTemplate(CreateTemplateRequest req, Long userId) {
        SafetyCheckScopeType scopeType = req.getScopeType() != null
                ? SafetyCheckScopeType.valueOf(req.getScopeType()) : null;

        SafetyCheckTemplateEntity entity = SafetyCheckTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(req.getScopeId())
                .templateName(req.getTemplateName())
                .title(req.getTitle())
                .message(req.getMessage())
                .reminderIntervalMinutes(req.getReminderIntervalMinutes())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        entity = templateRepository.save(entity);
        log.info("テンプレート作成: id={}, name={}", entity.getId(), entity.getTemplateName());
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを更新する。
     *
     * @param templateId テンプレートID
     * @param req        更新リクエスト
     * @return 更新されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse updateTemplate(Long templateId, UpdateTemplateRequest req) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);

        entity.update(req.getTemplateName(), req.getTitle(), req.getMessage(),
                req.getReminderIntervalMinutes(), req.getSortOrder());
        entity = templateRepository.save(entity);

        log.info("テンプレート更新: id={}", templateId);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを削除する。
     *
     * @param templateId テンプレートID
     */
    @Transactional
    public void deleteTemplate(Long templateId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        templateRepository.delete(entity);
        log.info("テンプレート削除: id={}", templateId);
    }

    /**
     * 全テンプレート一覧を取得する（管理者用）。
     *
     * @return テンプレート一覧
     */
    public List<SafetyTemplateResponse> listAllTemplates() {
        List<SafetyCheckTemplateEntity> entities = templateRepository.findAllByOrderBySortOrderAsc();
        return mapper.toTemplateResponseList(entities);
    }

    // --- プライベートメソッド ---

    /**
     * テンプレートを取得する。存在しない場合は例外をスローする。
     */
    private SafetyCheckTemplateEntity findTemplateOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
    }
}
