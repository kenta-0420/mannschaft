package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.KbTemplateScopeType;
import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import com.mannschaft.app.knowledgebase.repository.KbTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * ナレッジベーステンプレートサービス。
 * テンプレートのCRUD（システムテンプレートは変更不可）を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbTemplateService {

    /** テンプレート上限件数 */
    private static final int MAX_TEMPLATES = 20;

    private final KbTemplateRepository templateRepository;

    // ========================================
    // Request レコード型
    // ========================================

    public record CreateKbTemplateRequest(
            String name,
            String body,
            String icon
    ) {}

    public record UpdateKbTemplateRequest(
            String name,
            String body,
            String icon
    ) {}

    // ========================================
    // 参照系
    // ========================================

    /**
     * テンプレート一覧を取得する。
     * SYSTEMテンプレート + スコープのテンプレートを返す（deleted_at IS NULL）。
     */
    public ApiResponse<List<KbTemplateEntity>> getTemplates(String scopeType, Long scopeId) {
        List<KbTemplateEntity> systemTemplates =
                templateRepository.findByIsSystemTrueAndDeletedAtIsNull();

        List<KbTemplateEntity> scopeTemplates =
                templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);

        List<KbTemplateEntity> combined = new ArrayList<>();
        combined.addAll(systemTemplates);
        combined.addAll(scopeTemplates);

        return ApiResponse.of(combined);
    }

    // ========================================
    // 更新系
    // ========================================

    /**
     * テンプレートを作成する。
     * チーム/組織テンプレートが20件未満か確認する（KB_012）。
     */
    @Transactional
    public ApiResponse<KbTemplateEntity> createTemplate(String scopeType, Long scopeId,
                                                         Long createdBy,
                                                         CreateKbTemplateRequest req) {
        int currentCount = templateRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(
                scopeType, scopeId);
        if (currentCount >= MAX_TEMPLATES) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_012);
        }

        KbTemplateEntity template = KbTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(req.name())
                .body(req.body())
                .icon(req.icon())
                .isSystem(false)
                .createdBy(createdBy)
                .build();

        KbTemplateEntity saved = templateRepository.save(template);
        log.info("KBテンプレートを作成しました: id={}, name={}", saved.getId(), req.name());
        return ApiResponse.of(saved);
    }

    /**
     * テンプレートを更新する。
     * システムテンプレートは変更不可（KB_011）。versionチェックを行う。
     */
    @Transactional
    public ApiResponse<KbTemplateEntity> updateTemplate(Long id, String scopeType, Long scopeId,
                                                         UpdateKbTemplateRequest req, Long version) {
        KbTemplateEntity template = findTemplateByIdAndScope(id, scopeType, scopeId);

        if (Boolean.TRUE.equals(template.getIsSystem())) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_011);
        }

        if (!template.getVersion().equals(version)) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_006);
        }

        KbTemplateEntity updated = template.toBuilder()
                .name(req.name() != null ? req.name() : template.getName())
                .body(req.body() != null ? req.body() : template.getBody())
                .icon(req.icon() != null ? req.icon() : template.getIcon())
                .build();

        KbTemplateEntity saved = templateRepository.save(updated);
        log.info("KBテンプレートを更新しました: id={}", id);
        return ApiResponse.of(saved);
    }

    /**
     * テンプレートを論理削除する。
     * システムテンプレートは削除不可（KB_011）。
     */
    @Transactional
    public void deleteTemplate(Long id, String scopeType, Long scopeId) {
        KbTemplateEntity template = findTemplateByIdAndScope(id, scopeType, scopeId);

        if (Boolean.TRUE.equals(template.getIsSystem())) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_011);
        }

        template.softDelete();
        templateRepository.save(template);
        log.info("KBテンプレートを論理削除しました: id={}", id);
    }

    // ========================================
    // ヘルパー
    // ========================================

    private KbTemplateEntity findTemplateByIdAndScope(Long id, String scopeType, Long scopeId) {
        KbTemplateEntity template = templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_010));

        if (template.getDeletedAt() != null) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_010);
        }

        // システムテンプレートはスコープチェックを省略
        if (!Boolean.TRUE.equals(template.getIsSystem())) {
            if (!scopeType.equals(template.getScopeType())
                    || !scopeId.equals(template.getScopeId())) {
                throw new BusinessException(KnowledgeBaseErrorCode.KB_010);
            }
        }

        return template;
    }
}
