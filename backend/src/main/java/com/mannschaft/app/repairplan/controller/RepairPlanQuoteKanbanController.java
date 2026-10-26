package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.AddCardRequest;
import com.mannschaft.app.repairplan.dto.CreateKanbanRequest;
import com.mannschaft.app.repairplan.dto.MoveCardRequest;
import com.mannschaft.app.repairplan.dto.QuoteCardDto;
import com.mannschaft.app.repairplan.dto.QuoteKanbanDto;
import com.mannschaft.app.repairplan.dto.UpdateKanbanRequest;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.RepairPlanQuoteKanbanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * 相見積もりカンバン コントローラ（F08.8 Phase 4）。
 *
 * <p>URL 形式: {@code /api/v1/{scopeType}/{scopeId}/repair-plan/quote-kanbans}</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>GET    /quote-kanbans                     — カンバン一覧（メンバーシップ必須・visibility フィルタ適用）</li>
 *   <li>POST   /quote-kanbans                     — カンバン作成（ADMIN/DEPUTY_ADMIN 以上）</li>
 *   <li>GET    /quote-kanbans/{kanbanId}           — カンバン取得（メンバーシップ必須）</li>
 *   <li>PATCH  /quote-kanbans/{kanbanId}           — カンバン更新（ADMIN/DEPUTY_ADMIN 以上）</li>
 *   <li>POST   /quote-kanbans/{kanbanId}/cards     — カード追加（ADMIN/DEPUTY_ADMIN 以上）</li>
 *   <li>POST   /quote-cards/{cardId}/move          — カードステージ移動（ADMIN/DEPUTY_ADMIN 以上）</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <ul>
 *   <li>GET    — メンバーシップが必要（visibility フィルタはサービス層で適用）</li>
 *   <li>POST / PATCH — ADMIN/DEPUTY_ADMIN 以上</li>
 * </ul>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan")
@Tag(name = "相見積もりカンバン", description = "F08.8 Phase 4 — 業者見積管理カンバン")
@RequiredArgsConstructor
public class RepairPlanQuoteKanbanController {

    private final RepairPlanQuoteKanbanService service;
    private final AccessControlService accessControlService;

    /**
     * カンバン一覧を取得する（メンバーシップ必須・visibility フィルタ適用）。
     *
     * <p>非メンバーは {@code COMMON_002}（403 相当）で遮断する。SYSTEM_ADMIN は全件閲覧可。
     * 業者名・金額のマスキング（HIDDEN/ANONYMIZED/締切前）は閲覧者のロールに応じて
     * サービス層で適用する。</p>
     */
    @GetMapping("/quote-kanbans")
    @Operation(summary = "相見積もりカンバン一覧（メンバーシップ必須）")
    public ResponseEntity<ApiResponse<List<QuoteKanbanDto>>> listKanbans(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String normalizedScope = normalizeScope(scopeType);
        checkReadAccess(userId, scopeId, normalizedScope);
        List<QuoteKanbanDto> result = service.listKanbans(
                normalizedScope, scopeId, organizationId, userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * カンバンを作成する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping("/quote-kanbans")
    @Operation(summary = "相見積もりカンバン作成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<QuoteKanbanDto>> createKanban(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody CreateKanbanRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        QuoteKanbanDto dto = service.createKanban(
                normalizeScope(scopeType), scopeId, organizationId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * カンバンを 1 件取得する（メンバーシップ必須）。
     */
    @GetMapping("/quote-kanbans/{kanbanId}")
    @Operation(summary = "相見積もりカンバン取得")
    public ResponseEntity<ApiResponse<QuoteKanbanDto>> getKanban(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID kanbanId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String normalizedScope = normalizeScope(scopeType);
        checkReadAccess(userId, scopeId, normalizedScope);
        QuoteKanbanDto dto = service.getKanban(kanbanId, organizationId, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * カンバンを更新する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PatchMapping("/quote-kanbans/{kanbanId}")
    @Operation(summary = "相見積もりカンバン更新（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<QuoteKanbanDto>> updateKanban(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID kanbanId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody UpdateKanbanRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        QuoteKanbanDto dto = service.updateKanban(kanbanId, organizationId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * カードをカンバンに追加する（ADMIN/DEPUTY_ADMIN 以上）。
     *
     * <p>X-Organization-Id ヘッダ必須。反社チェック EXPIRED の業者は追加不可。</p>
     */
    @PostMapping("/quote-kanbans/{kanbanId}/cards")
    @Operation(summary = "見積カード追加（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<QuoteCardDto>> addCard(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID kanbanId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody AddCardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        QuoteCardDto dto = service.addCard(kanbanId, organizationId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * カードのステージを移動する（ADMIN/DEPUTY_ADMIN 以上）。
     *
     * <p>IDOR: card → kanban → scope の連鎖検証をサービス層で実施。</p>
     */
    @PostMapping("/quote-cards/{cardId}/move")
    @Operation(summary = "見積カードのステージ移動（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<QuoteCardDto>> moveCard(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID cardId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody MoveCardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        QuoteCardDto dto = service.moveCard(cardId, organizationId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * 読み取り（一覧・詳細）の認可を検証する。
     *
     * <p>SYSTEM_ADMIN は全スコープを横断的に閲覧できる。それ以外は対象スコープの
     * メンバー（MEMBER 以上）でなければ {@code COMMON_002}（403 相当）で遮断する。
     * これにより非メンバーが HIDDEN/匿名化/締切前の業者見積を取得する漏洩経路を断つ。</p>
     */
    private void checkReadAccess(Long userId, Long scopeId, String normalizedScope) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkMembership(userId, scopeId, normalizedScope);
    }

    /**
     * URL の {@code {scopeType}} を正規化する。
     */
    static String normalizeScope(String raw) {
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
