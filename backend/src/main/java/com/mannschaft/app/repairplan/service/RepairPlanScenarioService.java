package com.mannschaft.app.repairplan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.PinToCorkboardRequest;
import com.mannschaft.app.repairplan.dto.PinToCorkboardResponse;
import com.mannschaft.app.repairplan.dto.PublishAsAnnouncementRequest;
import com.mannschaft.app.repairplan.dto.PublishAsAnnouncementResponse;
import com.mannschaft.app.repairplan.dto.SaveScenarioRequest;
import com.mannschaft.app.repairplan.dto.ScenarioDto;
import com.mannschaft.app.repairplan.dto.SimulateRepairPlanRequest;
import com.mannschaft.app.repairplan.dto.SimulateRepairPlanResponse;
import com.mannschaft.app.repairplan.engine.RepairPlanSimulationEngine;
import com.mannschaft.app.repairplan.engine.SimulationParams;
import com.mannschaft.app.repairplan.engine.SimulationResult;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenario;
import com.mannschaft.app.repairplan.entity.RepairSimulationScenarioVersion;
import com.mannschaft.app.repairplan.repository.RepairFundBalanceRepository;
import com.mannschaft.app.repairplan.repository.RepairFundBalanceView;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.RepairSimulationScenarioRepository;
import com.mannschaft.app.repairplan.repository.RepairSimulationScenarioVersionRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオサービス（F08.8 Phase 2）。
 *
 * <p>シミュレーション計算・シナリオ保存・議案変換・コルクボードピン止めを提供する。</p>
 *
 * <h2>キャッシュ戦略</h2>
 * <p>simulate メソッドは content_sha256 をキーとして 60 秒間 Valkey にキャッシュする。
 * 同一パラメータ入力では同一結果が得られるため、連続呼び出し時の CPU 節約になる。</p>
 *
 * <h2>ドメイン境界</h2>
 * <p>F02.8 AnnouncementBroadcastService / F09.8 CorkboardService への依存は
 * ドメイン間境界をまたぐため、注入せずに TODO コメントで将来接続ポイントを示す。
 * publishedAnnouncementId / pinnedCorkboardId は null のまま保存し、後続フェーズで接続する。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepairPlanScenarioService {

    /** 1 スコープあたりのシナリオ保存上限。 */
    private static final int SCENARIO_LIMIT = 50;

    /** Valkey シミュレーション結果キャッシュ prefix。 */
    private static final String SIM_CACHE_PREFIX = "repairplan:simulate:";

    /** シミュレーション結果キャッシュの TTL。 */
    private static final Duration SIM_CACHE_TTL = Duration.ofSeconds(60);

    /** 許容するスコープ種別。 */
    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    private final RepairPlanSimulationEngine engine;
    private final RepairPlanItemRepository itemRepository;
    private final RepairFundBalanceRepository fundBalanceRepository;
    private final RepairSimulationScenarioRepository scenarioRepository;
    private final RepairSimulationScenarioVersionRepository versionRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // シミュレーション実行
    // =========================================================================

    /**
     * シミュレーションを実行する（保存なし）。
     *
     * <p>Bulkhead で同時実行数を 4 に制限する（重計算保護）。
     * content_sha256 が同一のリクエストは 60 秒間キャッシュから返す。</p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID
     * @param organizationId テナント組織 ID
     * @param request        シミュレーション入力パラメータ
     * @return シミュレーション結果
     */
    @Bulkhead(name = "simulation-pool", type = Bulkhead.Type.SEMAPHORE,
              fallbackMethod = "simulateFallback")
    public SimulateRepairPlanResponse simulate(String scopeType, Long scopeId,
                                                Long organizationId,
                                                SimulateRepairPlanRequest request) {
        validateScopeType(scopeType);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), scopeId, scopeType);

        SimulationParams params = buildParams(scopeType, scopeId, request);
        int baselineYear = request.baselineAt().getYear();

        // キャッシュキーはリクエストの JSON ハッシュを使う（engine 呼び出し前に決定できる）
        String requestHash = buildRequestHash(scopeType, scopeId, request);
        String cacheKey = buildCacheKey(scopeType, scopeId, requestHash);

        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                SimulateRepairPlanResponse cachedResponse =
                        objectMapper.readValue(cached, SimulateRepairPlanResponse.class);
                log.debug("シミュレーションキャッシュヒット: cacheKey={}", cacheKey);
                return cachedResponse;
            } catch (Exception e) {
                log.warn("シミュレーションキャッシュデシリアライズ失敗: cacheKey={}", cacheKey, e);
            }
        }

        SimulationResult result = engine.simulate(params, baselineYear);
        SimulateRepairPlanResponse response = toResponse(result);

        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(response), SIM_CACHE_TTL);
        } catch (Exception e) {
            log.warn("シミュレーションキャッシュ保存失敗: cacheKey={}", cacheKey, e);
        }

        return response;
    }

    /**
     * Bulkhead フォールバック: 同時実行上限超過時に RATE_LIMIT_EXCEEDED をスロー。
     */
    public SimulateRepairPlanResponse simulateFallback(String scopeType, Long scopeId,
                                                        Long organizationId,
                                                        SimulateRepairPlanRequest request,
                                                        BulkheadFullException e) {
        throw new BusinessException(RepairPlanErrorCode.RATE_LIMIT_EXCEEDED);
    }

    // =========================================================================
    // シナリオ保存
    // =========================================================================

    /**
     * シミュレーション結果をシナリオとして保存する。
     *
     * <p>1 スコープ 50 件上限チェック・content_sha256 重複チェックを行う。
     * name が null の場合は「シナリオ#N」として自動採番する。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープ ID
     * @param organizationId テナント組織 ID
     * @param request        シナリオ保存リクエスト
     * @param createdBy      作成者 ID
     * @return 保存したシナリオの DTO
     */
    @Transactional
    public ScenarioDto saveScenario(String scopeType, Long scopeId, Long organizationId,
                                     SaveScenarioRequest request, Long createdBy) {
        validateScopeType(scopeType);
        accessControlService.checkAdminOrAbove(createdBy, scopeId, scopeType);

        // シミュレーション実行
        SimulationParams params = buildParams(scopeType, scopeId, request.params());
        int baselineYear = request.params().baselineAt().getYear();
        SimulationResult result = engine.simulate(params, baselineYear);

        // 50 件上限チェック
        long count = scenarioRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        if (count >= SCENARIO_LIMIT) {
            throw new BusinessException(RepairPlanErrorCode.SCENARIO_LIMIT_EXCEEDED);
        }

        // content_sha256 重複チェック（UNIQUE KEY で DB でも弾かれるが事前確認）
        Optional<RepairSimulationScenario> duplicate =
                scenarioRepository.findByScopeTypeAndScopeIdAndContentSha256AndDeletedAtIsNull(
                        scopeType, scopeId, result.contentSha256());
        if (duplicate.isPresent()) {
            log.info("同一 content_sha256 のシナリオが存在します: sha256={}", result.contentSha256());
            return toDto(duplicate.get(), result);
        }

        // 名前の自動採番
        String name = request.name();
        if (name == null || name.isBlank()) {
            name = "シナリオ#" + (count + 1);
        }

        // params / computed_summary を JSON にシリアライズ
        String paramsJson = serializeJson(request.params());
        String summaryJson = serializeJson(result);

        RepairSimulationScenario scenario = RepairSimulationScenario.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .description(request.description())
                .paramsJson(paramsJson)
                .computedSummaryJson(summaryJson)
                .engineVersion(result.engineVersion())
                .contentSha256(result.contentSha256())
                .baselineAt(request.params().baselineAt())
                .createdBy(createdBy)
                .build();

        scenario = scenarioRepository.save(scenario);

        log.info("シナリオ保存: id={}, scope={}:{}, name={}", scenario.getId(), scopeType, scopeId, name);
        recordAudit(AuditEventType.SCENARIO_CREATED.name(), createdBy, scopeType, scopeId, organizationId,
                scenario.getId(), null);

        return toDto(scenario, result);
    }

    // =========================================================================
    // シナリオ取得
    // =========================================================================

    /**
     * スコープ単位のシナリオ一覧を取得する（最新順）。
     */
    public List<ScenarioDto> listScenarios(String scopeType, Long scopeId, Long organizationId) {
        validateScopeType(scopeType);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, scopeId, scopeType);

        List<RepairSimulationScenario> scenarios =
                scenarioRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        scopeType, scopeId);

        return scenarios.stream()
                .map(s -> toDtoFromJson(s))
                .toList();
    }

    /**
     * シナリオを 1 件取得する（IDOR 対策: org + scope で突合、認可根治戦役 Wave7: メンバーシップ必須）。
     *
     * <p>org + scope の突合に加え、兄弟の {@link #listScenarios} と同一の
     * {@link AccessControlService#checkMembership} により、対象スコープの会員であることを
     * 要求する。</p>
     */
    public ScenarioDto getScenario(UUID scenarioId, Long organizationId, Long userId) {
        RepairSimulationScenario scenario = findScenarioOrThrow(scenarioId, organizationId);
        accessControlService.checkMembership(userId, scenario.getScopeId(), scenario.getScopeType());
        return toDtoFromJson(scenario);
    }

    // =========================================================================
    // 議案変換（publishAsAnnouncement）
    // =========================================================================

    /**
     * シナリオを議案告知として公開・ロックする。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>シナリオ取得・認可確認</li>
     *   <li>locked_at が既にセット済みなら SCENARIO_ALREADY_LOCKED をスロー</li>
     *   <li>楽観ロック確認</li>
     *   <li>scenario_versions テーブルに INSERT（version_no = MAX+1）</li>
     *   <li>scenario.lockedAt をセット</li>
     *   <li>F02.8 AnnouncementBroadcastService への接続は将来フェーズ（現在は null）</li>
     * </ol>
     */
    @Transactional
    public PublishAsAnnouncementResponse publishAsAnnouncement(UUID scenarioId, Long organizationId,
                                                                PublishAsAnnouncementRequest request,
                                                                Long userId) {
        RepairSimulationScenario scenario = findScenarioOrThrow(scenarioId, organizationId);
        accessControlService.checkAdminOrAbove(userId, scenario.getScopeId(), scenario.getScopeType());

        // ロック済みチェック
        if (scenario.getLockedAt() != null) {
            throw new BusinessException(RepairPlanErrorCode.SCENARIO_ALREADY_LOCKED);
        }

        // 楽観ロック確認（version フィールド）
        if (!Objects.equals(scenario.getVersion(), request.version())) {
            throw new ObjectOptimisticLockingFailureException(RepairSimulationScenario.class, scenarioId);
        }

        // バージョン番号採番（既存最大 + 1）
        Optional<RepairSimulationScenarioVersion> latestVersion =
                versionRepository.findFirstByScenarioIdOrderByVersionNoDesc(scenarioId);
        int nextVersionNo = latestVersion.map(v -> v.getVersionNo() + 1).orElse(1);

        // バージョンスナップショット保存
        RepairSimulationScenarioVersion version = RepairSimulationScenarioVersion.builder()
                .organizationId(organizationId)
                .scenarioId(scenarioId)
                .versionNo(nextVersionNo)
                .paramsSnapshot(scenario.getParamsJson())
                .computedSummarySnapshot(scenario.getComputedSummaryJson())
                .engineVersion(scenario.getEngineVersion())
                .contentSha256(scenario.getContentSha256())
                .proposedResolutionNo(request.proposedResolutionNo())
                .lockedBy(userId)
                .build();
        versionRepository.save(version);

        // シナリオをロック
        LocalDateTime lockedAt = LocalDateTime.now();
        scenario.setLockedAt(lockedAt);
        scenarioRepository.save(scenario);

        // TODO: F02.8 AnnouncementBroadcastService への接続（将来フェーズ）
        // AnnouncementBroadcastService.broadcast() で告知を作成し、
        // scenario.setPublishedAnnouncementId(result.announcementFeedId()) を呼ぶ。
        // 現在は null のまま保存し、後続フェーズで接続する。
        Long announcementId = null;
        String announcementStatus = "PENDING";

        log.info("シナリオロック完了: id={}, versionNo={}, org={}", scenarioId, nextVersionNo, organizationId);
        recordAudit(AuditEventType.SCENARIO_LOCKED_FOR_PROPOSAL.name(), userId,
                scenario.getScopeType(), scenario.getScopeId(), organizationId,
                scenarioId, "{\"versionNo\":" + nextVersionNo + ",\"proposedResolutionNo\":\"" +
                        escapeJson(request.proposedResolutionNo()) + "\"}");
        recordAudit(AuditEventType.SCENARIO_PROPOSAL_CONVERTED.name(), userId,
                scenario.getScopeType(), scenario.getScopeId(), organizationId,
                scenarioId, "{\"announcementTitle\":\"" + escapeJson(request.announcementTitle()) + "\"}");

        return new PublishAsAnnouncementResponse(scenarioId, nextVersionNo, lockedAt,
                announcementId, announcementStatus);
    }

    // =========================================================================
    // コルクボードピン止め
    // =========================================================================

    /**
     * シナリオをコルクボードにピン止めする。
     *
     * <p>TODO: F09.8 CorkboardService への接続（将来フェーズ）。
     * 現在は pinnedCorkboardId を request.corkboardId() で保存するのみ。</p>
     */
    @Transactional
    public PinToCorkboardResponse pinToCorkboard(UUID scenarioId, Long organizationId,
                                                   PinToCorkboardRequest request, Long userId) {
        RepairSimulationScenario scenario = findScenarioOrThrow(scenarioId, organizationId);
        accessControlService.checkAdminOrAbove(userId, scenario.getScopeId(), scenario.getScopeType());

        // TODO: F09.8 CorkboardService のピン止めメソッドを呼ぶ（将来フェーズ）
        // CorkboardService.createScopedBoard() / カードへのピン等で実装予定。
        // 現在は corkboard_id を直接保存して後続フェーズで連携する。
        scenario.setPinnedCorkboardId(request.corkboardId());
        scenarioRepository.save(scenario);

        log.info("シナリオコルクボードピン: id={}, corkboardId={}", scenarioId, request.corkboardId());

        return new PinToCorkboardResponse(scenarioId, request.corkboardId());
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    private SimulationParams buildParams(String scopeType, Long scopeId,
                                          SimulateRepairPlanRequest req) {
        // 初期残高取得（ビューに存在しない場合は 0）
        BigDecimal initialBalance = fundBalanceRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(RepairFundBalanceView::getBalance)
                .orElse(BigDecimal.ZERO);

        // 年度別修繕費集計
        Map<Integer, BigDecimal> yearlyExpenses = new HashMap<>();
        for (Object[] row : itemRepository.sumEstimatedAmountByYear(scopeType, scopeId)) {
            Integer year = (Integer) row[0];
            BigDecimal sum = row[1] != null ? new BigDecimal(row[1].toString()) : BigDecimal.ZERO;
            yearlyExpenses.put(year, sum);
        }

        return new SimulationParams(
                initialBalance,
                req.monthlyFee(),
                req.dwellingUnits(),
                req.reserveInflationRate(),
                req.cpiInflationRate(),
                req.deferralYears(),
                req.loanPrincipal(),
                req.loanInterestRate(),
                req.loanTermYears(),
                req.fixedManagementCostYearly(),
                req.scenarioHorizonYears(),
                req.baselineAt(),
                yearlyExpenses
        );
    }

    private RepairSimulationScenario findScenarioOrThrow(UUID scenarioId, Long organizationId) {
        return scenarioRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(scenarioId, organizationId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.ITEM_NOT_FOUND));
    }

    private void validateScopeType(String scopeType) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType)) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
    }

    private String buildCacheKey(String scopeType, Long scopeId, String hash) {
        return SIM_CACHE_PREFIX + scopeType + ":" + scopeId + ":" + hash;
    }

    /**
     * シミュレーションリクエストのキャッシュキー用ハッシュを生成する。
     * DB 取得値（初期残高・修繕費集計）を含まず、リクエスト引数のみで決定する。
     */
    private String buildRequestHash(String scopeType, Long scopeId, SimulateRepairPlanRequest request) {
        try {
            String raw = scopeType + ":" + scopeId + ":" + objectMapper.writeValueAsString(request);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16); // 先頭16文字で十分
        } catch (Exception e) {
            return String.valueOf(request.hashCode());
        }
    }

    private String serializeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON シリアライズ失敗: {}", obj.getClass().getSimpleName(), e);
            return "{}";
        }
    }

    private SimulateRepairPlanResponse toResponse(SimulationResult result) {
        return new SimulateRepairPlanResponse(
                result.engineVersion(),
                result.contentSha256(),
                result.yearlyBalances(),
                result.depletionYear(),
                result.generationMeters(),
                result.warnings()
        );
    }

    /** Entity + SimulationResult からScenarioDto を生成する（新規保存直後に使用）。 */
    private ScenarioDto toDto(RepairSimulationScenario scenario, SimulationResult result) {
        return new ScenarioDto(
                scenario.getId(),
                scenario.getName(),
                scenario.getDescription(),
                result.engineVersion(),
                result.contentSha256(),
                result.yearlyBalances(),
                result.depletionYear(),
                result.generationMeters(),
                result.warnings(),
                scenario.getBaselineAt(),
                scenario.getLockedAt(),
                scenario.getPublishedAnnouncementId(),
                scenario.getPinnedCorkboardId(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt()
        );
    }

    /** Entity の computed_summary_json をデシリアライズして ScenarioDto を生成する（リスト取得時に使用）。 */
    private ScenarioDto toDtoFromJson(RepairSimulationScenario scenario) {
        try {
            SimulateRepairPlanResponse summary =
                    objectMapper.readValue(scenario.getComputedSummaryJson(),
                            SimulateRepairPlanResponse.class);
            return new ScenarioDto(
                    scenario.getId(),
                    scenario.getName(),
                    scenario.getDescription(),
                    summary.engineVersion(),
                    summary.contentSha256(),
                    summary.yearlyBalances(),
                    summary.depletionYear(),
                    summary.generationMeters(),
                    summary.warnings(),
                    scenario.getBaselineAt(),
                    scenario.getLockedAt(),
                    scenario.getPublishedAnnouncementId(),
                    scenario.getPinnedCorkboardId(),
                    scenario.getCreatedAt(),
                    scenario.getUpdatedAt()
            );
        } catch (Exception e) {
            log.warn("シナリオ computed_summary_json デシリアライズ失敗: id={}", scenario.getId(), e);
            return new ScenarioDto(
                    scenario.getId(), scenario.getName(), scenario.getDescription(),
                    scenario.getEngineVersion(), scenario.getContentSha256(),
                    List.of(), null, Map.of(), List.of(),
                    scenario.getBaselineAt(), scenario.getLockedAt(),
                    scenario.getPublishedAnnouncementId(), scenario.getPinnedCorkboardId(),
                    scenario.getCreatedAt(), scenario.getUpdatedAt()
            );
        }
    }

    private void recordAudit(String eventType, Long userId, String scopeType, Long scopeId,
                              Long organizationId, UUID scenarioId, String extraJson) {
        Long teamId = "TEAM".equals(scopeType) ? scopeId : null;
        Long orgId = "ORGANIZATION".equals(scopeType) ? scopeId : organizationId;
        String metadata = String.format(
                "{\"scenarioId\":\"%s\",\"scopeType\":\"%s\",\"scopeId\":%d%s}",
                scenarioId, scopeType, scopeId,
                extraJson != null ? "," + extraJson.substring(1, extraJson.length() - 1) : "");
        auditLogService.record(eventType, userId, null, teamId, orgId, null, null,
                SecurityUtils.getCurrentSessionHash(), metadata);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
