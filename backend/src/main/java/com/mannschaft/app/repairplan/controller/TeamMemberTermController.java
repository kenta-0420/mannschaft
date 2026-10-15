package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.dto.CreateTermRequest;
import com.mannschaft.app.repairplan.dto.TermDto;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.TeamMemberTermService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 理事任期コントローラ（F08.8 Phase 5）。
 *
 * <p>URL 形式: {@code /api/v1/teams/{teamId}/repair-plan/terms}</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>POST   / — 任期作成（ADMIN/DEPUTY_ADMIN 以上）</li>
 *   <li>GET    / — 任期一覧（メンバーシップ必須）</li>
 *   <li>GET    /{termId} — 任期取得（メンバーシップ必須）</li>
 *   <li>DELETE /{termId} — 任期削除（ADMIN 以上）</li>
 * </ul>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/teams/{teamId}/repair-plan/terms")
@Tag(name = "理事任期", description = "F08.8 Phase 5 — 理事任期管理")
@RequiredArgsConstructor
public class TeamMemberTermController {

    private final TeamMemberTermService service;

    /**
     * 理事任期を作成する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping
    @Operation(summary = "理事任期作成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<TermDto>> createTerm(
            @PathVariable Long teamId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody CreateTermRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        TermDto dto = service.createTerm(teamId, organizationId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 理事任期一覧を取得する（メンバーシップ必須）。
     */
    @GetMapping
    @Operation(summary = "理事任期一覧（メンバーシップ必須）")
    public ResponseEntity<ApiResponse<List<TermDto>>> listTerms(
            @PathVariable Long teamId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        List<TermDto> result = service.listTerms(teamId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 理事任期を 1 件取得する（メンバーシップ必須）。
     */
    @GetMapping("/{termId}")
    @Operation(summary = "理事任期取得")
    public ResponseEntity<ApiResponse<TermDto>> getTerm(
            @PathVariable Long teamId,
            @PathVariable UUID termId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        TermDto dto = service.getTerm(termId, teamId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * 理事任期を非アクティブ化する（ADMIN 以上）。
     */
    @DeleteMapping("/{termId}")
    @Operation(summary = "理事任期削除（ADMIN）")
    public ResponseEntity<Void> deleteTerm(
            @PathVariable Long teamId,
            @PathVariable UUID termId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.deleteTerm(termId, teamId, organizationId, userId);
        return ResponseEntity.noContent().build();
    }
}
