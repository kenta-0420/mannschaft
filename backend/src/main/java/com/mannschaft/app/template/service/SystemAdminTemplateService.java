package com.mannschaft.app.template.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.template.TemplateErrorCode;
import com.mannschaft.app.template.dto.CreateTemplateRequest;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.UpdateLevelAvailabilityRequest;
import com.mannschaft.app.template.dto.UpdateTemplateRequest;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SYSTEM_ADMIN向けテンプレート・モジュール管理サービス。
 * テンプレートCRUD・モジュールCRUD・レベル別利用可否更新を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class SystemAdminTemplateService {

    private final TeamTemplateRepository teamTemplateRepository;
    private final TemplateModuleRepository templateModuleRepository;
    private final ModuleDefinitionRepository moduleDefinitionRepository;
    private final ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository;

    /**
     * テンプレートを作成する。
     *
     * @param request 作成リクエスト
     * @param userId  操作ユーザーID
     * @return テンプレート詳細レスポンス
     */
    @Transactional
    public ApiResponse<TemplateResponse> createTemplate(CreateTemplateRequest request, Long userId) {
        TeamTemplateEntity template = TeamTemplateEntity.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .description(request.getDescription())
                .category(request.getCategory())
                .isActive(true)
                .createdBy(userId)
                .build();
        teamTemplateRepository.save(template);

        if (request.getModuleIds() != null) {
            for (Long moduleId : request.getModuleIds()) {
                TemplateModuleEntity tm = TemplateModuleEntity.builder()
                        .templateId(template.getId())
                        .moduleId(moduleId)
                        .build();
                templateModuleRepository.save(tm);
            }
        }

        log.info("テンプレート作成完了: templateId={}, userId={}", template.getId(), userId);
        List<ModuleSummaryResponse> modules = getModuleSummaries(template.getId());
        return ApiResponse.of(toResponse(template, modules));
    }

    /**
     * テンプレートを更新する。
     *
     * @param id      テンプレートID
     * @param request 更新リクエスト
     * @return テンプレート詳細レスポンス
     */
    @Transactional
    public ApiResponse<TemplateResponse> updateTemplate(Long id, UpdateTemplateRequest request) {
        TeamTemplateEntity template = findTemplateOrThrow(id);

        TeamTemplateEntity updated = template.toBuilder()
                .name(request.getName() != null ? request.getName() : template.getName())
                .description(request.getDescription() != null ? request.getDescription() : template.getDescription())
                .iconUrl(request.getIconUrl() != null ? request.getIconUrl() : template.getIconUrl())
                .category(request.getCategory() != null ? request.getCategory() : template.getCategory())
                .isActive(request.getIsActive() != null ? request.getIsActive() : template.getIsActive())
                .build();
        teamTemplateRepository.save(updated);

        if (request.getModuleIds() != null) {
            // 既存紐付けを削除して再作成
            List<TemplateModuleEntity> existing = templateModuleRepository.findByTemplateId(id);
            templateModuleRepository.deleteAll(existing);
            for (Long moduleId : request.getModuleIds()) {
                TemplateModuleEntity tm = TemplateModuleEntity.builder()
                        .templateId(id)
                        .moduleId(moduleId)
                        .build();
                templateModuleRepository.save(tm);
            }
        }

        log.info("テンプレート更新完了: templateId={}", id);
        List<ModuleSummaryResponse> modules = getModuleSummaries(id);
        return ApiResponse.of(toResponse(updated, modules));
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param id テンプレートID
     */
    @Transactional
    public void deleteTemplate(Long id) {
        TeamTemplateEntity template = findTemplateOrThrow(id);
        template.softDelete();
        log.info("テンプレート削除完了: templateId={}", id);
    }

    /**
     * モジュールのレベル別利用可否を更新する。
     *
     * @param moduleId モジュールID
     * @param request  更新リクエスト
     */
    @Transactional
    public void updateLevelAvailability(Long moduleId, UpdateLevelAvailabilityRequest request) {
        moduleDefinitionRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException(TemplateErrorCode.TMPL_002));

        ModuleLevelAvailabilityEntity.Level level = ModuleLevelAvailabilityEntity.Level.valueOf(request.getLevel());
        ModuleLevelAvailabilityEntity availability = moduleLevelAvailabilityRepository
                .findByModuleIdAndLevel(moduleId, level)
                .orElseThrow(() -> new BusinessException(TemplateErrorCode.TMPL_002));

        ModuleLevelAvailabilityEntity updated = availability.toBuilder()
                .isAvailable(request.isAvailable())
                .build();
        moduleLevelAvailabilityRepository.save(updated);

        log.info("レベル別利用可否更新完了: moduleId={}, level={}, isAvailable={}", moduleId, request.getLevel(), request.isAvailable());
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
