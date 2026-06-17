package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.dashboard.service.AdminActionRequiredFacade;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F10.1.1 / P1: 管理者向け横断「承認待ち」集約コントローラ。
 *
 * <p>メンバー向け {@code action-required}（{@link DashboardController}）とは別エンドポイント・別ファサード・
 * 別認可。パスは {@code /api/v1/dashboard/{team|organization}/{slug}/admin-action-required} とし、
 * メンバー向け {@code action-required} と同じ {@code /dashboard/{scope}/{id}/} 名前空間に揃える
 * （ダッシュボード集約 API ファミリー・設計書 03 §2）。</p>
 *
 * <p><b>認可</b>: 入口で {@link AdminActionRequiredFacade} が {@code checkAdminOrAbove} を必ず通す
 * （ADMIN/DEPUTY のみ・他テナント scopeId は非所属判定で 403・設計書 04 §2 / §5）。
 * パスの {@code slug} は {@code resolveTeamId}/{@code resolveOrgId} で内部 ID に解決する
 * （存在しない slug は TEAM_001/ORG_001 → 404）。</p>
 *
 * <p>GET 読み取りのため監査ログ対象外（設計書 04 §7）。</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "ダッシュボード（管理者承認待ち）")
@RequiredArgsConstructor
public class AdminActionRequiredController {

    /** プレビュー件数のデフォルト（設計書 03 §2.1）。 */
    private static final int DEFAULT_PREVIEW_SIZE = 3;
    /** プレビュー件数の上限（設計書 03 §2.1・§7）。 */
    private static final int MAX_PREVIEW_SIZE = 5;

    private final AdminActionRequiredFacade adminActionRequiredFacade;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * チームの横断承認待ち集約を取得する（予約/シフト/マッチング）。
     */
    @GetMapping("/team/{teamSlug}/admin-action-required")
    @Operation(summary = "チーム横断承認待ち集約",
            description = "ADMIN/DEPUTY 向け。予約承認待ち/シフトリクエスト/マッチング応募を集約。preview_size=0 で件数のみ")
    public ResponseEntity<ApiResponse<AdminActionRequiredResponse>> getTeamAdminActionRequired(
            @PathVariable String teamSlug,
            @RequestParam(name = "preview_size", required = false) Integer previewSize) {
        int size = resolvePreviewSize(previewSize);
        Long userId = SecurityUtils.getCurrentUserId();
        Long teamId = teamService.resolveTeamId(teamSlug);
        AdminActionRequiredResponse response =
                adminActionRequiredFacade.getAdminActionRequired(userId, "TEAM", teamId, teamSlug, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織の横断承認待ち集約を取得する（未収請求のみ）。
     */
    @GetMapping("/organization/{orgSlug}/admin-action-required")
    @Operation(summary = "組織横断承認待ち集約",
            description = "ADMIN/DEPUTY 向け。組織が発行した未収請求（SENT/VIEWED/OVERDUE）を集約。preview_size=0 で件数のみ")
    public ResponseEntity<ApiResponse<AdminActionRequiredResponse>> getOrgAdminActionRequired(
            @PathVariable String orgSlug,
            @RequestParam(name = "preview_size", required = false) Integer previewSize) {
        int size = resolvePreviewSize(previewSize);
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(orgSlug);
        AdminActionRequiredResponse response =
                adminActionRequiredFacade.getAdminActionRequired(userId, "ORGANIZATION", orgId, orgSlug, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * preview_size を検証して解決する。未指定はデフォルト 3。範囲外（0 未満 / 5 超）は 400（COMMON_001）。
     */
    private int resolvePreviewSize(Integer previewSize) {
        if (previewSize == null) {
            return DEFAULT_PREVIEW_SIZE;
        }
        if (previewSize < 0 || previewSize > MAX_PREVIEW_SIZE) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return previewSize;
    }
}
