package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.MemberWorkConstraintRequest;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import com.mannschaft.app.shift.service.MemberWorkConstraintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * メンバー勤務制約コントローラ（F03.5 v2 新規）。
 *
 * <p>チーム単位のデフォルト勤務制約と、メンバー個別オーバーライドの
 * CRUD API を提供する。権限モデルは {@link MemberWorkConstraintService} を参照。</p>
 */
@RestController
@RequestMapping("/api/v1/shifts/teams/{teamId}/work-constraints")
@Tag(name = "シフト勤務制約管理", description = "F03.5 v2: メンバー勤務制約の CRUD")
@RequiredArgsConstructor
public class MemberWorkConstraintController {

    private final MemberWorkConstraintService workConstraintService;

    // ========================================
    // 一覧取得（ADMIN/DEPUTY_ADMIN のみ）
    // ========================================

    /**
     * チーム内の全勤務制約（デフォルト + 個別）を取得する。
     */
    @GetMapping
    @Operation(summary = "チーム勤務制約一覧", description = "デフォルト + 個別オーバーライドを一括取得（ADMIN/DEPUTY_ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MemberWorkConstraintResponse>>> listConstraints(
            @PathVariable Long teamId) {
        List<MemberWorkConstraintResponse> list = workConstraintService
                .listConstraintsByTeam(teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    // ========================================
    // 個別制約
    // ========================================

    /**
     * メンバー個別の勤務制約を取得する（個別 → デフォルトの解決順序）。
     *
     * <p>本人または ADMIN/DEPUTY_ADMIN のみアクセス可。</p>
     */
    @GetMapping("/members/{userId}")
    @Operation(summary = "メンバー勤務制約取得", description = "本人 or ADMIN/DEPUTY_ADMIN のみ。個別→デフォルトの解決順序で返却")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MemberWorkConstraintResponse>> getConstraint(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        MemberWorkConstraintResponse response = workConstraintService
                .getConstraint(teamId, userId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー個別の勤務制約を upsert する。
     */
    @PutMapping("/members/{userId}")
    @Operation(summary = "メンバー勤務制約 upsert", description = "ADMIN/DEPUTY_ADMIN のみ。全項目 NULL は 400")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberWorkConstraintResponse>> upsertConstraint(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @Valid @RequestBody MemberWorkConstraintRequest request) {
        MemberWorkConstraintResponse response = workConstraintService
                .upsertConstraint(teamId, userId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー個別の勤務制約を削除する。
     */
    @DeleteMapping("/members/{userId}")
    @Operation(summary = "メンバー勤務制約削除", description = "ADMIN/DEPUTY_ADMIN のみ。削除後はチームデフォルトにフォールバック")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteConstraint(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        workConstraintService.deleteConstraint(teamId, userId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // チームデフォルト
    // ========================================

    /**
     * チームデフォルトを取得する。チームメンバー全員が閲覧可（透明性のため）。
     */
    @GetMapping("/default")
    @Operation(summary = "チームデフォルト勤務制約取得", description = "チームメンバー全員が閲覧可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MemberWorkConstraintResponse>> getTeamDefault(
            @PathVariable Long teamId) {
        MemberWorkConstraintResponse response = workConstraintService
                .getTeamDefault(teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームデフォルトを upsert する。
     */
    @PutMapping("/default")
    @Operation(summary = "チームデフォルト勤務制約 upsert", description = "ADMIN/DEPUTY_ADMIN のみ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberWorkConstraintResponse>> upsertTeamDefault(
            @PathVariable Long teamId,
            @Valid @RequestBody MemberWorkConstraintRequest request) {
        MemberWorkConstraintResponse response = workConstraintService
                .upsertTeamDefault(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームデフォルトを削除する。
     */
    @DeleteMapping("/default")
    @Operation(summary = "チームデフォルト勤務制約削除", description = "ADMIN/DEPUTY_ADMIN のみ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTeamDefault(
            @PathVariable Long teamId) {
        workConstraintService.deleteTeamDefault(teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
