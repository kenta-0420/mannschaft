package com.mannschaft.app.template.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.TemplateErrorCode;
import com.mannschaft.app.template.dto.LevelAvailabilityResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.OrganizationEnabledModuleEntity;
import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.ModuleRecommendationRepository;
import com.mannschaft.app.template.repository.OrganizationEnabledModuleRepository;
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
    private final OrganizationEnabledModuleRepository organizationEnabledModuleRepository;
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
    @Cacheable(value = "teamModules", key = "#teamId", unless = "#result == null || #result.isEmpty()")
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
            existing.applyToggle(
                    request.isEnabled(),
                    request.isEnabled() ? now : existing.getEnabledAt(),
                    !request.isEnabled() ? now : null,
                    userId);
            teamEnabledModuleRepository.save(existing);
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
    // 組織スコープ（組織モジュール管理）
    // ========================================

    /**
     * 組織の有効モジュール一覧を返す（DEFAULTモジュール含む）。
     *
     * @param orgId 組織ID
     * @return 組織モジュールレスポンスリスト
     */
    @Cacheable(value = "orgModules", key = "#orgId")
    public List<OrgModuleResponse> getOrganizationModules(Long orgId) {
        return organizationEnabledModuleRepository.findByOrganizationId(orgId).stream()
                .map(oem -> {
                    ModuleDefinitionEntity module = moduleDefinitionRepository.findById(oem.getModuleId())
                            .orElse(null);
                    if (module == null) {
                        return null;
                    }
                    return new OrgModuleResponse(
                            module.getId(),
                            module.getName(),
                            module.getSlug(),
                            oem.getIsEnabled(),
                            oem.getEnabledAt());
                })
                .filter(r -> r != null)
                .toList();
    }

    /**
     * 組織のモジュール有効/無効を切り替える。
     * DEFAULTモジュールは切替不可（TMPL_006）。
     * ORGANIZATIONスコープで is_available=0 のモジュールは切替不可（TMPL_005）。
     * 無料プラン上限: 有効OPTIONALモジュールが10個に達している場合は切替不可（TMPL_003）。
     *
     * @param orgId   組織ID
     * @param request トグルリクエスト
     * @param userId  操作ユーザーID
     */
    @Transactional
    @CacheEvict(value = "orgModules", key = "#orgId")
    public void toggleOrganizationModule(Long orgId, ToggleModuleRequest request, Long userId) {
        ModuleDefinitionEntity module = findModuleOrThrow(request.getModuleId());

        // DEFAULTモジュールは設定変更不可
        if (module.getModuleType() == ModuleDefinitionEntity.ModuleType.DEFAULT) {
            throw new BusinessException(TemplateErrorCode.TMPL_006);
        }

        // レベルチェック（ORGANIZATIONレベルで利用可能か）
        moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION)
                .ifPresent(availability -> {
                    if (!availability.getIsAvailable()) {
                        throw new BusinessException(TemplateErrorCode.TMPL_005);
                    }
                });

        if (request.isEnabled()) {
            // 無料上限チェック
            long enabledCount = organizationEnabledModuleRepository
                    .countByOrganizationIdAndIsEnabledTrue(orgId);
            if (enabledCount >= FREE_PLAN_MODULE_LIMIT) {
                throw new BusinessException(TemplateErrorCode.TMPL_003);
            }
        }

        OrganizationEnabledModuleEntity existing = organizationEnabledModuleRepository
                .findByOrganizationIdAndModuleId(orgId, request.getModuleId())
                .orElse(null);

        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.applyToggle(
                    request.isEnabled(),
                    request.isEnabled() ? now : existing.getEnabledAt(),
                    !request.isEnabled() ? now : null,
                    userId);
            organizationEnabledModuleRepository.save(existing);
        } else {
            OrganizationEnabledModuleEntity newEntity = OrganizationEnabledModuleEntity.builder()
                    .organizationId(orgId)
                    .moduleId(request.getModuleId())
                    .isEnabled(request.isEnabled())
                    .enabledAt(request.isEnabled() ? now : null)
                    .enabledBy(userId)
                    .build();
            organizationEnabledModuleRepository.save(newEntity);
        }

        log.info("組織モジュール切替完了: orgId={}, moduleId={}, enabled={}", orgId, request.getModuleId(), request.isEnabled());
    }

    /**
     * 指定組織でモジュールが有効かどうかを判定する（サイドバー表示制御用）。
     * DEFAULT型は常にtrue。OPTIONAL型はorganization_enabled_modulesを参照する。
     *
     * @param moduleSlug モジュールスラッグ
     * @param orgId      組織ID
     * @return 有効な場合 true
     */
    public boolean isModuleEnabledForOrg(String moduleSlug, Long orgId) {
        ModuleDefinitionEntity module = moduleDefinitionRepository.findBySlug(moduleSlug).orElse(null);
        if (module == null || !module.getIsActive()) {
            return false;
        }
        // DEFAULTモジュールは常に有効
        if (module.getModuleType() == ModuleDefinitionEntity.ModuleType.DEFAULT) {
            return true;
        }
        // 選択式モジュールは組織有効化状態を確認
        return organizationEnabledModuleRepository
                .findByOrganizationIdAndModuleId(orgId, module.getId())
                .map(OrganizationEnabledModuleEntity::getIsEnabled)
                .orElse(false);
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
