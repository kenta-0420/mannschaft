package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.dto.AdminAdjustPointRequest;
import com.mannschaft.app.gamification.dto.PointSummaryResponse;
import com.mannschaft.app.gamification.dto.PointTransactionResponse;
import com.mannschaft.app.gamification.service.GamificationPointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ゲーミフィケーション・ポイントコントローラー。
 * チームスコープのポイントサマリー・履歴・調整・リセットAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/points")
@RequiredArgsConstructor
public class GamificationPointController {

    private final GamificationPointService gamificationPointService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * 自分のポイントサマリーを取得する。
     *
     * @param teamId チームID
     * @return ポイントサマリー
     */
    @GetMapping("/me")
    public ApiResponse<PointSummaryResponse> getMyPointSummary(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        GamificationPointService.PointSummaryResult result = gamificationPointService
                .getMyPointSummary(userId, "TEAM", teamId)
                .getData();
        PointSummaryResponse response = new PointSummaryResponse(
                result.totalPoints(),
                result.weeklyPoints(),
                result.monthlyPoints(),
                result.yearlyPoints()
        );
        return ApiResponse.of(response);
    }

    /**
     * 自分のポイント履歴をカーソルページネーションで取得する。
     *
     * @param teamId チームID
     * @param cursor カーソル（前回取得の最後のID）
     * @param limit  取得件数（デフォルト20）
     * @return ポイント履歴
     */
    @GetMapping("/me/history")
    public CursorPagedResponse<PointTransactionResponse> getMyPointHistory(
            @PathVariable Long teamId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");

        CursorPagedResponse<com.mannschaft.app.gamification.entity.PointTransactionEntity> entityResponse =
                gamificationPointService.getMyPointHistory(userId, "TEAM", teamId, cursor, limit);

        List<PointTransactionResponse> dtoList = entityResponse.getData().stream()
                .map(gamificationMapper::toTransactionResponse)
                .toList();

        return CursorPagedResponse.of(dtoList, entityResponse.getMeta());
    }

    /**
     * 管理者によるポイント調整を行う。
     *
     * @param teamId  チームID
     * @param request 調整リクエスト
     */
    @PostMapping("/adjust")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminAdjust(
            @PathVariable Long teamId,
            @Valid @RequestBody AdminAdjustPointRequest request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(adminId, teamId, "TEAM");
        gamificationPointService.adminAdjustPoint(
                request.userId(), "TEAM", teamId, request.points(), adminId);
    }

    /**
     * 管理者によるスコープ全ユーザーのポイントリセットを行う。
     *
     * @param teamId チームID
     */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminReset(@PathVariable Long teamId) {
        Long adminId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(adminId, teamId, "TEAM");
        gamificationPointService.adminResetPoints("TEAM", teamId, adminId);
    }
}
