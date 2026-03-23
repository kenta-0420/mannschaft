package com.mannschaft.app.template.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.TemplateErrorCode;
import com.mannschaft.app.template.dto.LevelAvailabilityResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.ModuleRecommendationRepository;
import com.mannschaft.app.template.repository.TeamEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * モジュール管理サービス。モジュールカタログ参照・チームモジュール有効化を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ModuleService {

    private static final int FREE_PLAN_MODULE_LIMIT = 10;

    private final ModuleDefinitionRepository moduleDefinitionRepository;
    private final ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository;
    private final ModuleRecommendationRepository moduleRecommendationRepository;
    private final TeamEnabledModuleRepository teamEnabledModuleRepository;
    private final TemplateModuleRepository templateModuleRepository;
    private final TeamTemplateRepository teamTemplateRepository;
    private final TeamPlanService teamPlanService;

    /**
     * 選択式モジュールカタログを取得する（OPTIONAL + is_active のみ）。
     *
     * @return モジュール詳細リスト
     */
    @Cacheable(value = "moduleCatalog")
    public List<ModuleResponse> getModuleCatalog() {
        return moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .stream()
                .filter(ModuleDefinitionEntity::getIsActive)
                .map(this::toModuleResponse)
                .toList();
    }

    /**
     * モジュール詳細を取得する。
     *
     * @param id モジュールID
     * @return モジュール詳細レスポンス
     */
    @Cacheable(value = "moduleDetail", key = "#id")
    public ApiResponse<ModuleResponse> getModule(Long id) {
        ModuleDefinitionEntity module = findModuleOrThrow(id);
        return ApiResponse.of(toModuleResponse(module));
    }

    /**
     * チームの有効モジュール一覧を取得する。
     *
     * @param teamId チームID
     * @return チームモジュールレスポンスリスト
     */
    @Cacheable(value = "teamModules", key = "#teamId")
    public List<TeamModuleResponse> getTeamModules(Long teamId) {
        return teamEnabledModuleRepository.findByTeamId(teamId).stream()
                .map(tem -> {
                    ModuleDefinitionEntity module = moduleDefinitionRepository.findById(tem.getModuleId())
                            .orElse(null);
                    if (module == null) {
                        return null;
                    }
                    return new TeamModuleResponse(
                            module.getId(),
                            module.getName(),
                            module.getSlug(),
                            tem.getIsEnabled(),
                            tem.getEnabledAt(),
                            tem.getTrialExpiresAt());
                })
                .filter(r -> r != null)
                .toList();
    }

    /**
     * チームのモジュール有効/無効を切り替える。
     * 無料上限10チェック、有料プランチェック、レベルチェックを実施する。
     *
     * @param teamId  チームID
     * @param request トグルリクエスト
     * @param userId  操作ユーザーID
     */
    @Transactional
    @CacheEvict(value = "teamModules", key = "#teamId")
    public void toggleTeamModule(Long teamId, ToggleModuleRequest request, Long userId) {
        ModuleDefinitionEntity module = findModuleOrThrow(request.getModuleId());

        // レベルチェック（TEAMレベルで利用可能か）
        moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM)
                .ifPresent(availability -> {
                    if (!availability.getIsAvailable()) {
                        throw new BusinessException(TemplateErrorCode.TMPL_005);
                    }
                });

        if (request.isEnabled()) {
            // 有料プランチェック
            if (module.getRequiresPaidPlan() && !teamPlanService.hasPaidPlan(teamId)) {
                throw new BusinessException(TemplateErrorCode.TMPL_004);
            }

            // 無料上限チェック
            long enabledCount = teamEnabledModuleRepository.findByTeamId(teamId).stream()
                    .filter(TeamEnabledModuleEntity::getIsEnabled)
                    .count();
            if (enabledCount >= FREE_PLAN_MODULE_LIMIT) {
                throw new BusinessException(TemplateErrorCode.TMPL_003);
            }
        }

        TeamEnabledModuleEntity existing = teamEnabledModuleRepository
                .findByTeamIdAndModuleId(teamId, request.getModuleId())
                .orElse(null);

        if (existing != null) {
            LocalDateTime now = LocalDateTime.now();
            TeamEnabledModuleEntity updated = existing.toBuilder()
                    .isEnabled(request.isEnabled())
                    .enabledAt(request.isEnabled() ? now : existing.getEnabledAt())
                    .disabledAt(!request.isEnabled() ? now : null)
                    .enabledBy(userId)
                    .build();
            teamEnabledModuleRepository.save(updated);
        } else {
            LocalDateTime now = LocalDateTime.now();
            TeamEnabledModuleEntity newEntity = TeamEnabledModuleEntity.builder()
                    .teamId(teamId)
                    .moduleId(request.getModuleId())
                    .isEnabled(request.isEnabled())
                    .enabledAt(request.isEnabled() ? now : null)
                    .enabledBy(userId)
                    .trialUsed(false)
                    .trialExpiresAt(module.getTrialDays() != null && module.getTrialDays() > 0
                            ? now.plusDays(module.getTrialDays()) : null)
                    .build();
            teamEnabledModuleRepository.save(newEntity);
        }

        log.info("モジュール切替完了: teamId={}, moduleId={}, enabled={}", teamId, request.getModuleId(), request.isEnabled());
    }

    /**
     * テンプレートの推奨モジュールをチームに自動適用する。
     *
     * @param teamId     チームID
     * @param templateId テンプレートID
     * @param userId     操作ユーザーID
     */
    @Transactional
    @CacheEvict(value = "teamModules", key = "#teamId")
    public void applyTemplate(Long teamId, Long templateId, Long userId) {
        teamTemplateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(TemplateErrorCode.TMPL_001));

        List<TemplateModuleEntity> templateModules = templateModuleRepository.findByTemplateId(templateId);
        LocalDateTime now = LocalDateTime.now();

        for (TemplateModuleEntity tm : templateModules) {
            if (teamEnabledModuleRepository.findByTeamIdAndModuleId(teamId, tm.getModuleId()).isPresent()) {
                continue;
            }
            ModuleDefinitionEntity module = moduleDefinitionRepository.findById(tm.getModuleId()).orElse(null);
            if (module == null) {
                continue;
            }
            TeamEnabledModuleEntity newEntity = TeamEnabledModuleEntity.builder()
                    .teamId(teamId)
                    .moduleId(tm.getModuleId())
                    .isEnabled(true)
                    .enabledAt(now)
                    .enabledBy(userId)
                    .trialUsed(false)
                    .trialExpiresAt(module.getTrialDays() != null && module.getTrialDays() > 0
                            ? now.plusDays(module.getTrialDays()) : null)
                    .build();
            teamEnabledModuleRepository.save(newEntity);
        }

        log.info("テンプレート適用完了: teamId={}, templateId={}", teamId, templateId);
    }

    /**
     * 指定スコープでモジュールが有効かどうかを判定する。
     * DEFAULT モジュールは常に有効。OPTIONAL モジュールはチーム単位で有効化状態を確認する。
     *
     * @param moduleSlug モジュールスラッグ
     * @param teamId     チームID（OPTIONALモジュールの判定に使用）
     * @return 有効な場合 true
     */
    public boolean isModuleEnabledForTeam(String moduleSlug, Long teamId) {
        ModuleDefinitionEntity module = moduleDefinitionRepository.findBySlug(moduleSlug).orElse(null);
        if (module == null || !module.getIsActive()) {
            return false;
        }
        // デフォルト機能は常に有効
        if (module.getModuleType() == ModuleDefinitionEntity.ModuleType.DEFAULT) {
            return true;
        }
        // 選択式モジュールはチーム有効化状態を確認
        return teamEnabledModuleRepository.findByTeamIdAndModuleId(teamId, module.getId())
                .map(TeamEnabledModuleEntity::getIsEnabled)
                .orElse(false);
    }

    /**
     * モジュール無効理由を返す。有効な場合は null。
     *
     * @param moduleSlug モジュールスラッグ
     * @param teamId     チームID
     * @return 無効理由（null = 有効）
     */
    public String getModuleDisabledReason(String moduleSlug, Long teamId) {
        ModuleDefinitionEntity module = moduleDefinitionRepository.findBySlug(moduleSlug).orElse(null);
        if (module == null) {
            return "モジュールが存在しません";
        }
        if (!module.getIsActive()) {
            return "モジュールが無効化されています";
        }
        if (module.getModuleType() == ModuleDefinitionEntity.ModuleType.DEFAULT) {
            return null;
        }
        return teamEnabledModuleRepository.findByTeamIdAndModuleId(teamId, module.getId())
                .map(tem -> tem.getIsEnabled() ? null : "このチームでは未有効化です")
                .orElse("このチームでは未有効化です");
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private ModuleDefinitionEntity findModuleOrThrow(Long id) {
        return moduleDefinitionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(TemplateErrorCode.TMPL_002));
    }

    private ModuleResponse toModuleResponse(ModuleDefinitionEntity module) {
        List<LevelAvailabilityResponse> levels = moduleLevelAvailabilityRepository
                .findByModuleId(module.getId()).stream()
                .map(la -> new LevelAvailabilityResponse(
                        la.getLevel().name(), la.getIsAvailable(), la.getNote()))
                .toList();

        List<ModuleSummaryResponse> recs = moduleRecommendationRepository
                .findByModuleId(module.getId()).stream()
                .map(rec -> moduleDefinitionRepository.findById(rec.getRecommendedModuleId()).orElse(null))
                .filter(m -> m != null)
                .map(m -> new ModuleSummaryResponse(
                        m.getId(), m.getName(), m.getSlug(), m.getModuleType().name()))
                .toList();

        return new ModuleResponse(
                module.getId(),
                module.getName(),
                module.getSlug(),
                module.getDescription(),
                module.getModuleType().name(),
                module.getModuleNumber(),
                module.getRequiresPaidPlan(),
                module.getTrialDays(),
                module.getIsActive(),
                levels,
                recs);
    }
}
