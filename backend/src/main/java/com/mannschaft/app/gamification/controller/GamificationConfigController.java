package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.dto.GamificationConfigResponse;
import com.mannschaft.app.gamification.dto.UpdateGamificationConfigRequest;
import com.mannschaft.app.gamification.service.GamificationConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ゲーミフィケーション設定コントローラー。
 * チームスコープのゲーミフィケーション設定取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/config")
@RequiredArgsConstructor
public class GamificationConfigController {

    private final GamificationConfigService gamificationConfigService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * チームのゲーミフィケーション設定を取得する。
     *
     * @param teamId チームID
     * @return ゲーミフィケーション設定
     */
    @GetMapping
    public ApiResponse<GamificationConfigResponse> get(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toConfigResponse(
                        gamificationConfigService.getConfig("TEAM", teamId).getData()
                )
        );
    }

    /**
     * チームのゲーミフィケーション設定を更新する。
     *
     * @param teamId  チームID
     * @param request 更新リクエスト
     * @return 更新後のゲーミフィケーション設定
     */
    @PutMapping
    public ApiResponse<GamificationConfigResponse> update(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateGamificationConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toConfigResponse(
                        gamificationConfigService.updateConfig(
                                "TEAM", teamId,
                                request.isEnabled(),
                                request.isRankingEnabled(),
                                request.rankingDisplayCount(),
                                request.pointResetMonth(),
                                request.version()
                        ).getData()
                )
        );
    }
}
