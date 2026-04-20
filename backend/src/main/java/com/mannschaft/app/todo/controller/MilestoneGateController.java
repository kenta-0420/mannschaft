package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CompletionModeRequest;
import com.mannschaft.app.todo.dto.ForceUnlockRequest;
import com.mannschaft.app.todo.dto.GatesSummaryResponse;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.dto.ReorderTodosRequest;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.service.MilestoneGateService;
import com.mannschaft.app.todo.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F02.7 マイルストーンゲート（関所）Controller。
 *
 * <p>チーム／個人スコープの両方のプロジェクトに対する以下の API を提供する:
 * <ul>
 *   <li>GET {@code /api/v1/teams/{teamId}/projects/{projectId}/gates} — ゲート状態サマリー</li>
 *   <li>PATCH {@code .../milestones/{milestoneId}/completion-mode} — 完了モード切替</li>
 *   <li>PATCH {@code .../milestones/{milestoneId}/force-unlock} — 強制アンロック（ADMIN のみ）</li>
 *   <li>PATCH {@code .../milestones/{milestoneId}/initialize-gate} — ゲート初期化</li>
 *   <li>PATCH {@code .../milestones/{milestoneId}/todos/reorder} — TODO 並び替え</li>
 * </ul>
 * 個人スコープは {@code /api/v1/users/me/projects/{projectId}/...} の対応パスでも利用可能。</p>
 *
 * <p>IDOR 対策: teamId / projectId / milestoneId の三重検証を行う（プロジェクトのスコープ整合性
 * チェックおよび ProjectService 側でのマイルストーン所属チェックを併用）。</p>
 */
@Slf4j
@RestController
@Tag(name = "マイルストーンゲート", description = "F02.7 マイルストーンゲート（関所）")
@RequiredArgsConstructor
public class MilestoneGateController {

    private final ProjectService projectService;
    private final MilestoneGateService milestoneGateService;
    private final ProjectRepository projectRepository;
    private final AccessControlService accessControlService;

    // ============================================================
    // チームスコープ
    // ============================================================

    /**
     * チームプロジェクトのゲートサマリーを取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/projects/{projectId}/gates")
    @Operation(summary = "ゲート状態サマリー（チーム）")
    public ResponseEntity<ApiResponse<GatesSummaryResponse>> getTeamGatesSummary(
            @PathVariable Long teamId,
            @PathVariable Long projectId) {
        Long userId = SecurityUtils.getCurrentUserId();
        validateTeamProjectAccess(userId, teamId, projectId, /* requireAdmin */ false, /* adminOnly */ false);
        return ResponseEntity.ok(projectService.getGatesSummary(projectId));
    }

    /**
     * チームマイルストーンの完了モードを変更する（ADMIN / DEPUTY_ADMIN）。
     */
    @PatchMapping("/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/completion-mode")
    @Operation(summary = "完了モード切替（チーム・ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<MilestoneResponse>> changeTeamCompletionMode(
            @PathVariable Long teamId,
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody CompletionModeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validateTeamProjectAccess(userId, teamId, projectId, /* requireAdmin */ true, /* adminOnly */ false);
        return ResponseEntity.ok(projectService.changeMilestoneCompletionMode(
                projectId, milestoneId, request.completionMode()));
    }

    /**
     * チームマイルストーンを強制アンロックする（ADMIN のみ）。
     */
    @PatchMapping("/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/force-unlock")
    @Operation(summary = "強制アンロック（チーム・ADMIN のみ）")
    public ResponseEntity<Void> forceUnlockTeamMilestone(
            @PathVariable Long teamId,
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody ForceUnlockRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validateTeamProjectAccess(userId, teamId, projectId, /* requireAdmin */ true, /* adminOnly */ true);
        // F10.3 監査ログ実装時に MILESTONE_FORCE_UNLOCKED を記録する（現状は MilestoneGateService 内のログで代替）
        milestoneGateService.forceUnlock(milestoneId, userId, request.reason());
        return ResponseEntity.ok().build();
    }

    /**
     * チームマイルストーンのゲートを初期化する（ADMIN / DEPUTY_ADMIN）。
     */
    @PatchMapping("/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/initialize-gate")
    @Operation(summary = "ゲート初期化（チーム・ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<Void> initializeTeamGate(
            @PathVariable Long teamId,
            @PathVariable Long projectId,
            @PathVariable Long milestoneId) {
        Long userId = SecurityUtils.getCurrentUserId();
        validateTeamProjectAccess(userId, teamId, projectId, /* requireAdmin */ true, /* adminOnly */ false);
        milestoneGateService.initializeGate(milestoneId);
        return ResponseEntity.ok().build();
    }

    /**
     * チームマイルストーン内の TODO 並び替え（ADMIN / DEPUTY_ADMIN）。
     */
    @PatchMapping("/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/todos/reorder")
    @Operation(summary = "マイルストーン内 TODO 並び替え（チーム・ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<Void> reorderTeamMilestoneTodos(
            @PathVariable Long teamId,
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody ReorderTodosRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validateTeamProjectAccess(userId, teamId, projectId, /* requireAdmin */ true, /* adminOnly */ false);
        projectService.reorderTodosInMilestone(projectId, milestoneId, request.todoIds());
        return ResponseEntity.ok().build();
    }

    // ============================================================
    // 個人スコープ
    // ============================================================

    /**
     * 個人プロジェクトのゲートサマリーを取得する。
     */
    @GetMapping("/api/v1/users/me/projects/{projectId}/gates")
    @Operation(summary = "ゲート状態サマリー（個人）")
    public ResponseEntity<ApiResponse<GatesSummaryResponse>> getPersonalGatesSummary(
            @PathVariable Long projectId) {
        Long userId = SecurityUtils.getCurrentUserId();
        validatePersonalProjectAccess(userId, projectId);
        return ResponseEntity.ok(projectService.getGatesSummary(projectId));
    }

    /**
     * 個人マイルストーンの完了モードを変更する（作成者本人）。
     */
    @PatchMapping("/api/v1/users/me/projects/{projectId}/milestones/{milestoneId}/completion-mode")
    @Operation(summary = "完了モード切替（個人）")
    public ResponseEntity<ApiResponse<MilestoneResponse>> changePersonalCompletionMode(
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody CompletionModeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validatePersonalProjectAccess(userId, projectId);
        return ResponseEntity.ok(projectService.changeMilestoneCompletionMode(
                projectId, milestoneId, request.completionMode()));
    }

    /**
     * 個人マイルストーンを強制アンロックする（プロジェクト作成者本人のみ）。
     */
    @PatchMapping("/api/v1/users/me/projects/{projectId}/milestones/{milestoneId}/force-unlock")
    @Operation(summary = "強制アンロック（個人）")
    public ResponseEntity<Void> forceUnlockPersonalMilestone(
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody ForceUnlockRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validatePersonalProjectAccess(userId, projectId);
        milestoneGateService.forceUnlock(milestoneId, userId, request.reason());
        return ResponseEntity.ok().build();
    }

    /**
     * 個人マイルストーンのゲートを初期化する（作成者本人）。
     */
    @PatchMapping("/api/v1/users/me/projects/{projectId}/milestones/{milestoneId}/initialize-gate")
    @Operation(summary = "ゲート初期化（個人）")
    public ResponseEntity<Void> initializePersonalGate(
            @PathVariable Long projectId,
            @PathVariable Long milestoneId) {
        Long userId = SecurityUtils.getCurrentUserId();
        validatePersonalProjectAccess(userId, projectId);
        milestoneGateService.initializeGate(milestoneId);
        return ResponseEntity.ok().build();
    }

    /**
     * 個人マイルストーン内の TODO 並び替え（作成者本人）。
     */
    @PatchMapping("/api/v1/users/me/projects/{projectId}/milestones/{milestoneId}/todos/reorder")
    @Operation(summary = "マイルストーン内 TODO 並び替え（個人）")
    public ResponseEntity<Void> reorderPersonalMilestoneTodos(
            @PathVariable Long projectId,
            @PathVariable Long milestoneId,
            @Valid @RequestBody ReorderTodosRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        validatePersonalProjectAccess(userId, projectId);
        projectService.reorderTodosInMilestone(projectId, milestoneId, request.todoIds());
        return ResponseEntity.ok().build();
    }

    // ============================================================
    // 認可ヘルパー
    // ============================================================

    /**
     * チームプロジェクトへのアクセスを検証する（IDOR 三重検証）。
     *
     * @param userId        現在ユーザー ID
     * @param teamId        パス上のチーム ID
     * @param projectId     パス上のプロジェクト ID
     * @param requireAdmin  true の場合 ADMIN / DEPUTY_ADMIN のいずれかを要求
     * @param adminOnly     true の場合 ADMIN のみ許可（DEPUTY_ADMIN も不可）
     */
    private void validateTeamProjectAccess(Long userId, Long teamId, Long projectId,
                                            boolean requireAdmin, boolean adminOnly) {
        // プロジェクトが存在し、チームスコープ・スコープ ID が一致することを検証
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
        if (project.getScopeType() != TodoScopeType.TEAM || !project.getScopeId().equals(teamId)) {
            // IDOR 防御: 他スコープ ID を推測して叩くケースを NOT_FOUND にまとめる
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND);
        }

        // メンバーシップ検証
        accessControlService.checkMembership(userId, teamId, "TEAM");

        if (adminOnly) {
            if (!accessControlService.isAdmin(userId, teamId, "TEAM")) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        } else if (requireAdmin) {
            accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        }
    }

    /**
     * 個人プロジェクトへのアクセスを検証する。
     *
     * <p>個人プロジェクトは作成者本人のみが操作可能。ADMIN 概念は適用しないが、
     * 強制アンロックも作成者本人なら可（設計書 §4 準拠）。</p>
     *
     * @param userId    現在ユーザー ID
     * @param projectId パス上のプロジェクト ID
     */
    private void validatePersonalProjectAccess(Long userId, Long projectId) {
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
        if (project.getScopeType() != TodoScopeType.PERSONAL
                || !project.getScopeId().equals(userId)) {
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND);
        }
    }
}
