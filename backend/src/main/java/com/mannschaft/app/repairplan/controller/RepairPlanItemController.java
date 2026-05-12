package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.CreateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.dto.RepairPlanItemDto;
import com.mannschaft.app.repairplan.dto.RepairPlanItemFilter;
import com.mannschaft.app.repairplan.dto.UpdateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.service.RepairPlanItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * 修繕計画項目 CRUD コントローラ（F08.8 Phase 1 案5）。
 *
 * <p>URL 形式: {@code /api/v1/{scopeType}/{scopeId}/repair-plan/items}
 * （{@code scopeType} は大文字小文字を問わず受け付け、内部で {@code TEAM} / {@code ORGANIZATION} に正規化する）。</p>
 *
 * <h2>認可</h2>
 * <ul>
 *   <li>{@code GET} — メンバーシップが必要</li>
 *   <li>{@code POST / PATCH / DELETE} — ADMIN/DEPUTY_ADMIN 以上</li>
 * </ul>
 *
 * <h2>楽観ロック</h2>
 * <p>PATCH / DELETE は {@code If-Match} ヘッダで {@code version} 値を渡す。
 * サーバ側の {@code version} と異なる場合は 409 を返す。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan/items")
@Tag(name = "修繕計画項目", description = "F08.8 Phase 1 案5 — 修繕計画項目リスト CRUD")
@RequiredArgsConstructor
public class RepairPlanItemController {

    private final RepairPlanItemService service;

    @PostMapping
    @Operation(summary = "計画項目作成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<RepairPlanItemDto>> create(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody CreateRepairPlanItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        RepairPlanItemDto dto = service.create(request, userId, scopeId, normalizeScope(scopeType), organizationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    @GetMapping
    @Operation(summary = "計画項目一覧（メンバーシップ必須）")
    public ResponseEntity<PagedResponse<RepairPlanItemDto>> list(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam(required = false) Integer plannedYear,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);

        RepairPlanItemFilter filter = RepairPlanItemFilter.builder()
                .plannedYear(plannedYear)
                .category(category)
                .status(status)
                .build();

        Page<RepairPlanItemDto> result = service.list(
                scopeId,
                normalizeScope(scopeType),
                organizationId,
                filter,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "plannedYear")
                        .and(Sort.by(Sort.Direction.ASC, "plannedMonth"))),
                userId);

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), safePage, safeSize, result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @GetMapping("/{id}")
    @Operation(summary = "計画項目取得")
    public ResponseEntity<ApiResponse<RepairPlanItemDto>> get(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID id,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                service.get(id, organizationId, normalizeScope(scopeType), scopeId, userId)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "計画項目更新（ADMIN/DEPUTY_ADMIN、If-Match 必須）")
    public ResponseEntity<ApiResponse<RepairPlanItemDto>> update(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID id,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRepairPlanItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long version = parseIfMatch(ifMatch);
        RepairPlanItemDto dto = service.update(
                id, request, userId, organizationId, normalizeScope(scopeType), scopeId, version);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "計画項目削除（ADMIN/DEPUTY_ADMIN、If-Match 必須）")
    public ResponseEntity<Void> delete(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID id,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long version = parseIfMatch(ifMatch);
        service.softDelete(id, userId, organizationId, normalizeScope(scopeType), scopeId, version);
        return ResponseEntity.noContent().build();
    }

    /**
     * URL の {@code {scopeType}} を {@code TEAM} / {@code ORGANIZATION} に正規化する。
     * 大文字小文字や省略形（{@code team}, {@code teams}, {@code organization}, {@code organizations}）を受け付ける。
     */
    private static String normalizeScope(String raw) {
        if (raw == null) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.equals("TEAM") || upper.equals("TEAMS")) {
            return "TEAM";
        }
        if (upper.equals("ORGANIZATION") || upper.equals("ORGANIZATIONS") || upper.equals("ORG") || upper.equals("ORGS")) {
            return "ORGANIZATION";
        }
        throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
    }

    /**
     * {@code If-Match} ヘッダ文字列から {@code version} 値を取り出す。
     * 値が空・null の場合は null を返す（楽観ロックスキップ）。書式不正は 400 で返す。
     */
    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String trimmed = ifMatch.trim();
        // RFC 7232 ETag の弱い検証子 / クォートを許容する
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
    }
}
