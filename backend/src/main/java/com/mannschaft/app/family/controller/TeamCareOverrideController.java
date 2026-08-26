package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.family.dto.TeamCareOverrideRequest;
import com.mannschaft.app.family.dto.TeamCareOverrideResponse;
import com.mannschaft.app.family.service.CareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームケア通知上書き設定コントローラー。
 * チームごとのケア通知フラグ上書き設定を管理する。F03.12。
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 3 EP すべてが
 * {@code CareLinkService#requireCareLinkParty} を通る。careLinkId で取得した
 * <b>entity 由来の当事者 ID</b>（ケア対象者本人・見守り者）と認証主体を照合し、
 * 当事者以外は<b>チーム ADMIN であっても</b> {@code FAMILY_030}（403）で拒否する
 * （続柄・ケア区分を含む後見系の機微情報のため）。不存在の careLinkId は
 * {@code FAMILY_025}（404）で存在を秘匿する。パスの {@code teamId} は認可判定に使わず、
 * 上書き設定の絞り込みキーとしてのみ用いる。契約は
 * {@code CareLinkInvitationScopeContractIT} で固定する。</p>
 */
@AuthorizedInService
@RestController
@RequestMapping("/api/v1/teams/{teamId}/care-overrides")
@Tag(name = "チームケア通知上書き", description = "F03.12 チーム別ケア通知上書き設定")
@RequiredArgsConstructor
public class TeamCareOverrideController {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final CareLinkService careLinkService;

    /**
     * チームのケア通知上書き設定を取得する。
     */
    @GetMapping("/{careLinkId}")
    @Operation(summary = "チームケア通知上書き設定取得")
    public ResponseEntity<ApiResponse<TeamCareOverrideResponse>> getTeamOverride(
            @PathVariable Long teamId,
            @PathVariable Long careLinkId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        TeamCareOverrideResponse response =
                careLinkService.getTeamOverride(SCOPE_TYPE_TEAM, teamId, careLinkId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームのケア通知上書き設定を作成または更新する（upsert）。
     */
    @PutMapping("/{careLinkId}")
    @Operation(summary = "チームケア通知上書き設定 upsert")
    public ResponseEntity<ApiResponse<TeamCareOverrideResponse>> upsertTeamOverride(
            @PathVariable Long teamId,
            @PathVariable Long careLinkId,
            @RequestBody TeamCareOverrideRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.upsertTeamOverride(
                        SCOPE_TYPE_TEAM, teamId, careLinkId, currentUserId, request)));
    }

    /**
     * チームのケア通知上書き設定を削除する。
     */
    @DeleteMapping("/{careLinkId}")
    @Operation(summary = "チームケア通知上書き設定削除")
    public ResponseEntity<Void> deleteTeamOverride(
            @PathVariable Long teamId,
            @PathVariable Long careLinkId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        careLinkService.deleteTeamOverride(SCOPE_TYPE_TEAM, teamId, careLinkId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
