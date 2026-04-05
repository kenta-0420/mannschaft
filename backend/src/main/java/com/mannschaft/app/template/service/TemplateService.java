package com.mannschaft.app.template.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.template.TemplateErrorCode;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.TemplateSummaryResponse;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * テンプレート管理サービス。テンプレートの参照機能を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private final TeamTemplateRepository teamTemplateRepository;
    private final TemplateModuleRepository templateModuleRepository;
    private final ModuleDefinitionRepository moduleDefinitionRepository;

    /**
     * アクティブなテンプレート一覧を取得する。
     *
     * @return テンプレートサマリーリスト
     */
    @Cacheable(value = "templates")
    public List<TemplateSummaryResponse> getTemplates() {
        return teamTemplateRepository.findByIsActiveTrue().stream()
                .map(template -> {
                    int moduleCount = templateModuleRepository.findByTemplateId(template.getId()).size();
                    return new TemplateSummaryResponse(
                            template.getId(),
                            template.getName(),
                            template.getSlug(),
                            template.getCategory(),
                            moduleCount);
                })
                .toList();
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param id テンプレートID
     * @return テンプレート詳細レスポンス
     */
    @Cacheable(value = "templateDetail", key = "#id")
    public ApiResponse<TemplateResponse> getTemplate(Long id) {
        TeamTemplateEntity template = findTemplateOrThrow(id);
        List<ModuleSummaryResponse> modules = getModuleSummaries(id);
        return ApiResponse.of(toResponse(template, modules));
    }

    /**
     * テンプレートに紐付くモジュール一覧を取得する。
     *
     * @param id テンプレートID
     * @return モジュールサマリーリスト
     */
    @Cacheable(value = "templateModules", key = "#id")
    public List<ModuleSummaryResponse> getTemplateModules(Long id) {
        findTemplateOrThrow(id);
        return getModuleSummaries(id);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private TeamTemplateEntity findTemplateOrThrow(Long id) {
        return teamTemplateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(TemplateErrorCode.TMPL_001));
    }

    private List<ModuleSummaryResponse> getModuleSummaries(Long templateId) {
        return templateModuleRepository.findByTemplateId(templateId).stream()
                .map(tm -> moduleDefinitionRepository.findById(tm.getModuleId()).orElse(null))
                .filter(m -> m != null)
                .map(m -> new ModuleSummaryResponse(
                        m.getId(), m.getName(), m.getSlug(), m.getModuleType().name()))
                .toList();
    }

    private TemplateResponse toResponse(TeamTemplateEntity template, List<ModuleSummaryResponse> modules) {
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getSlug(),
                template.getDescription(),
                template.getIconUrl(),
                template.getCategory(),
                template.getIsActive(),
                modules,
                template.getCreatedAt());
    }
}
