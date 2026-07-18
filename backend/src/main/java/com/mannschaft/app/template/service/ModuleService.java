package com.mannschaft.app.template.service;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.TemplateErrorCode;
import com.mannschaft.app.template.dto.LevelAvailabilityResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.OrgModuleCatalogItem;
import com.mannschaft.app.template.dto.OrgModuleCatalogResponse;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.TeamModuleCatalogItem;
import com.mannschaft.app.template.dto.TeamModuleCatalogResponse;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final EntitlementQueryService entitlementQueryService;

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
     * SYSTEM_ADMIN 管理画面向けに全モジュールを取得する。
     *
     * <p>tenant 向けの {@link #getModuleCatalog()} が「OPTIONAL かつ is_active」に絞り込むのに対し、
     * こちらは <b>DEFAULT/OPTIONAL・is_active の true/false を問わず全件</b>を moduleNumber 昇順で返す。
     * これにより管理画面に DEFAULT モジュールも表示され、is_active=false へトグルした行も
     * 一覧に残り続けて再有効化できる（無効化しても画面から消えない）。</p>
     *
     * <p>キャッシュは付けない。管理画面は低トラフィックで、有料要否/有効状態トグル直後の
     * 反映を常に保証すべきため、常に最新を DB から読む。論理削除は
     * {@code @SQLRestriction("deleted_at IS NULL")} により自動除外される。</p>
     *
     * @return 全モジュール詳細リスト（moduleNumber 昇順）
     */
    public List<ModuleResponse> getAllModulesForAdmin() {
        return moduleDefinitionRepository.findAllByOrderByModuleNumberAsc()
                .stream()
                .map(this::toModuleResponse)
                .toList();
    }

    /**
     * チームの機能設定タブ向けカタログ＋有効状態を取得する。
     *
     * <p>利用可能な OPTIONAL かつ active なモジュール全件に対し、当該チームでの有効化状態・
     * トライアル期限・チームレベル利用可否を結合して返す。enabledCount は有効化済み件数、
     * planLimit は無料プラン上限、hasPaidPlan は有料プラン加入状況を含める。</p>
     *
     * @param teamId チームID
     * @return チームカタログ＋有効状態レスポンス（modules は moduleNumber 昇順）
     */
    public TeamModuleCatalogResponse getTeamModuleCatalog(Long teamId) {
        // 有効化行を一括取得し moduleId で引けるよう Map 化（N+1 回避）
        Map<Long, TeamEnabledModuleEntity> enabledByModuleId = teamEnabledModuleRepository.findByTeamId(teamId)
                .stream()
                .collect(Collectors.toMap(TeamEnabledModuleEntity::getModuleId, Function.identity(), (a, b) -> a));

        List<TeamModuleCatalogItem> items = activeOptionalModules().stream()
                .map(module -> {
                    TeamEnabledModuleEntity row = enabledByModuleId.get(module.getId());
                    boolean enabled = row != null && Boolean.TRUE.equals(row.getIsEnabled());
                    LocalDateTime trialExpiresAt = row != null ? row.getTrialExpiresAt() : null;
                    return TeamModuleCatalogItem.builder()
                            .moduleId(module.getId())
                            .name(module.getName())
                            .slug(module.getSlug())
                            .description(module.getDescription())
                            .moduleNumber(module.getModuleNumber())
                            .isEnabled(enabled)
                            .requiresPaidPlan(module.getRequiresPaidPlan())
                            .levelAvailable(isLevelAvailable(module.getId(),
                                    ModuleLevelAvailabilityEntity.Level.TEAM))
                            .trialExpiresAt(trialExpiresAt)
                            .build();
                })
                .toList();

        // 表示用「X/10 使用中」の使用数。上限強制カウントと同一定義に揃え、grandfather 行は数えない
        // （表示層でも grandfather を除外しないと FE の追加ボタンが閉じ AC-3 が表示層で崩れるため）。
        long enabledCount = enabledByModuleId.values().stream()
                .filter(row -> Boolean.TRUE.equals(row.getIsEnabled()))
                .filter(row -> !Boolean.TRUE.equals(row.getIsGrandfathered()))
                .count();

        return TeamModuleCatalogResponse.builder()
                .planLimit(FREE_PLAN_MODULE_LIMIT)
                .enabledCount(enabledCount)
                .hasPaidPlan(teamPlanService.hasPaidPlan(teamId))
                .modules(items)
                .build();
    }

    /**
     * 組織の機能設定タブ向けカタログ＋有効状態を取得する。
     *
     * <p>チーム版と対称。組織側には有料プラン判定が存在しないため hasPaidPlan は常に false、
     * トライアル期限も持たない。利用可否判定は ORGANIZATION レベルで行う。</p>
     *
     * @param orgId 組織ID
     * @return 組織カタログ＋有効状態レスポンス（modules は moduleNumber 昇順）
     */
    public OrgModuleCatalogResponse getOrganizationModuleCatalog(Long orgId) {
        Map<Long, OrganizationEnabledModuleEntity> enabledByModuleId =
                organizationEnabledModuleRepository.findByOrganizationId(orgId)
                        .stream()
                        .collect(Collectors.toMap(OrganizationEnabledModuleEntity::getModuleId,
                                Function.identity(), (a, b) -> a));

        List<OrgModuleCatalogItem> items = activeOptionalModules().stream()
                .map(module -> {
                    OrganizationEnabledModuleEntity row = enabledByModuleId.get(module.getId());
                    boolean enabled = row != null && Boolean.TRUE.equals(row.getIsEnabled());
                    return OrgModuleCatalogItem.builder()
                            .moduleId(module.getId())
                            .name(module.getName())
                            .slug(module.getSlug())
                            .description(module.getDescription())
                            .moduleNumber(module.getModuleNumber())
                            .isEnabled(enabled)
                            .requiresPaidPlan(module.getRequiresPaidPlan())
                            .levelAvailable(isLevelAvailable(module.getId(),
                                    ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                            .build();
                })
                .toList();

        // 表示用「X/10 使用中」の使用数。上限強制カウント（countBy...IsGrandfatheredFalse）と同一定義に
        // 揃え、grandfather 行は数えない（表示層でも除外しないと AC-3 が表示層で崩れるため）。
        long enabledCount = enabledByModuleId.values().stream()
                .filter(row -> Boolean.TRUE.equals(row.getIsEnabled()))
                .filter(row -> !Boolean.TRUE.equals(row.getIsGrandfathered()))
                .count();

        return OrgModuleCatalogResponse.builder()
                .planLimit(FREE_PLAN_MODULE_LIMIT)
                .enabledCount(enabledCount)
                // 組織側に有料プラン判定が存在しないため false 固定
                .hasPaidPlan(false)
                .modules(items)
                .build();
    }

    /**
     * OPTIONAL かつ active なモジュールを moduleNumber 昇順で返す（カタログ母集合）。
     */
    private List<ModuleDefinitionEntity> activeOptionalModules() {
        return moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .stream()
                .filter(ModuleDefinitionEntity::getIsActive)
                .sorted(Comparator.comparing(ModuleDefinitionEntity::getModuleNumber))
                .toList();
    }

    /**
     * 指定レベルでモジュールが利用可能か判定する。
     * module_level_availability にレコードが無い場合は「制約なし＝利用可」とみなす。
     */
    private boolean isLevelAvailable(Long moduleId, ModuleLevelAvailabilityEntity.Level level) {
        return moduleLevelAvailabilityRepository.findByModuleIdAndLevel(moduleId, level)
                .map(ModuleLevelAvailabilityEntity::getIsAvailable)
                .orElse(true);
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
            // 有料プランチェック（F20.1: 有料判定を isEntitled に置換・TMPL_004 は不変で維持し FE 後方互換を保つ）。
            // 既存有料チームは後方互換ブリッジ（team_subscriptions ACTIVE → FULL 契約 → plan_features 全キー）で
            // premium entitlement を保持するため機能は失われない。
            if (module.getRequiresPaidPlan() && !entitlementQueryService.isEntitled(
                    EntitlementScopeKind.TEAM, teamId, FeatureKeys.TEMPLATE_PREMIUM_MODULES)) {
                throw new BusinessException(TemplateErrorCode.TMPL_004);
            }

            // 無料上限チェック。
            // グランドファザリング行（is_grandfathered=1）は既得機能として上限カウントから除外する
            // （既存テナントが既得機能で無料枠を消費し新規有効化できなくなる事故の根治）。
            long enabledCount = teamEnabledModuleRepository.findByTeamId(teamId).stream()
                    .filter(row -> Boolean.TRUE.equals(row.getIsEnabled()))
                    .filter(row -> !Boolean.TRUE.equals(row.getIsGrandfathered()))
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
            // 有料プランチェック（チーム側 toggleTeamModule と対称・穴の根治）。
            // 有料モジュールは premium entitlement（scope=ORG）を保持していなければ有効化不可。
            // scope は組織なので EntitlementScopeKind.ORG + orgId を渡す（チーム側は TEAM + teamId）。
            if (module.getRequiresPaidPlan() && !entitlementQueryService.isEntitled(
                    EntitlementScopeKind.ORG, orgId, FeatureKeys.TEMPLATE_PREMIUM_MODULES)) {
                throw new BusinessException(TemplateErrorCode.TMPL_004);
            }

            // 無料上限チェック。
            // グランドファザリング行（is_grandfathered=1）は既得機能として上限カウントから除外する
            // （既存テナントが既得機能で無料枠を消費し新規有効化できなくなる事故の根治）。
            long enabledCount = organizationEnabledModuleRepository
                    .countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(orgId);
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
