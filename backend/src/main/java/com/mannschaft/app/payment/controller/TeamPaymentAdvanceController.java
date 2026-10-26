package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.TeamPaymentAdvanceResponse;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.service.TeamPaymentAdvanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F08.9 P7 第二波: チームの立替/精算記録コントローラー（案3・02_api §7）。
 *
 * <p>チーム ADMIN が立替/精算一覧を閲覧し、立替金の精算（チーム→ADMIN 返金）を確認する（PENDING → SETTLED）。
 * 認可・IDOR は {@link TeamPaymentAdvanceService} 内部（{@code requireTeamAdmin}・team_id 一致）で行う。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/payment-advances")
@Tag(name = "立替/精算（チーム視点）", description = "F08.9 P7 協会請求の立替/精算記録の一覧・精算確認")
@RequiredArgsConstructor
public class TeamPaymentAdvanceController {

    private final TeamPaymentAdvanceService teamPaymentAdvanceService;

    /**
     * チームの立替/精算記録一覧を取得する（新しい順）。
     */
    @GetMapping
    @Operation(summary = "立替/精算記録の一覧")
    public ResponseEntity<ApiResponse<List<TeamPaymentAdvanceResponse>>> list(@PathVariable Long teamId) {
        List<TeamPaymentAdvanceEntity> advances = teamPaymentAdvanceService.findForTeam(
                teamId, SecurityUtils.getCurrentUserId());
        List<TeamPaymentAdvanceResponse> content = advances.stream()
                .map(TeamPaymentAdvanceResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(content));
    }

    /**
     * 立替金の精算を確認する（PENDING → SETTLED）。
     */
    @PostMapping("/{id}/confirm-settlement")
    @Operation(summary = "立替金の精算確認（SETTLED）")
    public ResponseEntity<ApiResponse<TeamPaymentAdvanceResponse>> confirmSettlement(
            @PathVariable Long teamId,
            @PathVariable UUID id) {
        TeamPaymentAdvanceEntity settled = teamPaymentAdvanceService.confirmSettlement(
                teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(TeamPaymentAdvanceResponse.from(settled)));
    }
}
