package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
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
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.RepairPlanScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 修繕シミュレーションシナリオ コントローラ（F08.8 Phase 2）。
 *
 * <p>URL 形式: {@code /api/v1/{scopeType}/{scopeId}/repair-plan/scenarios}</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>POST  /simulate — シミュレーション実行（保存なし）</li>
 *   <li>POST  / — シナリオ保存</li>
 *   <li>GET   / — シナリオ一覧</li>
 *   <li>GET   /{scenarioId} — シナリオ取得</li>
 *   <li>POST  /{scenarioId}/publish-as-announcement — 議案告知として公開・ロック</li>
 *   <li>POST  /{scenarioId}/pin-to-corkboard — コルクボードへのピン止め</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <ul>
 *   <li>GET / simulate — メンバーシップが必要</li>
 *   <li>POST / publish / pin — ADMIN/DEPUTY_ADMIN 以上</li>
 * </ul>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan/scenarios")
@Tag(name = "修繕シミュレーションシナリオ", description = "F08.8 Phase 2 — 積立金枯渇シミュレーション・シナリオ管理")
@RequiredArgsConstructor
public class RepairPlanScenarioController {

    private final RepairPlanScenarioService service;

    /**
     * シミュレーションを実行する（保存なし）。
     * レートリミット: RepairPlanSimulateRateLimitFilter が担当（20 req/min/user, 100 req/min/scope）。
     */
    @PostMapping("/simulate")
    @Operation(summary = "積立金枯渇シミュレーション実行（保存なし）")
    public ResponseEntity<ApiResponse<SimulateRepairPlanResponse>> simulate(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody SimulateRepairPlanRequest request) {
        SimulateRepairPlanResponse result = service.simulate(
                normalizeScope(scopeType), scopeId, organizationId, request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * シナリオを保存する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping
    @Operation(summary = "シナリオ保存（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<ScenarioDto>> saveScenario(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody SaveScenarioRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScenarioDto dto = service.saveScenario(
                normalizeScope(scopeType), scopeId, organizationId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * シナリオ一覧を取得する（メンバーシップ必須）。
     */
    @GetMapping
    @Operation(summary = "シナリオ一覧（メンバーシップ必須）")
    public ResponseEntity<ApiResponse<List<ScenarioDto>>> listScenarios(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        List<ScenarioDto> result = service.listScenarios(
                normalizeScope(scopeType), scopeId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * シナリオを 1 件取得する（メンバーシップ必須）。
     */
    @GetMapping("/{scenarioId}")
    @Operation(summary = "シナリオ取得")
    public ResponseEntity<ApiResponse<ScenarioDto>> getScenario(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID scenarioId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScenarioDto dto = service.getScenario(scenarioId, organizationId, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * シナリオを議案告知として公開・ロックする（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping("/{scenarioId}/publish-as-announcement")
    @Operation(summary = "シナリオを議案告知として公開（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<PublishAsAnnouncementResponse>> publishAsAnnouncement(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID scenarioId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody PublishAsAnnouncementRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PublishAsAnnouncementResponse result = service.publishAsAnnouncement(
                scenarioId, organizationId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * シナリオをコルクボードにピン止めする（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping("/{scenarioId}/pin-to-corkboard")
    @Operation(summary = "シナリオをコルクボードにピン止め（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<PinToCorkboardResponse>> pinToCorkboard(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID scenarioId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody PinToCorkboardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PinToCorkboardResponse result = service.pinToCorkboard(
                scenarioId, organizationId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * URL の {@code {scopeType}} を正規化する。
     */
    private static String normalizeScope(String raw) {
        if (raw == null) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.equals("TEAM") || upper.equals("TEAMS")) {
            return "TEAM";
        }
        if (upper.equals("ORGANIZATION") || upper.equals("ORGANIZATIONS")
                || upper.equals("ORG") || upper.equals("ORGS")) {
            return "ORGANIZATION";
        }
        throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
    }
}
