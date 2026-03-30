package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.dto.GamificationUserSettingResponse;
import com.mannschaft.app.gamification.dto.UpdateGamificationUserSettingRequest;
import com.mannschaft.app.gamification.service.GamificationSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ゲーミフィケーション・ユーザー設定コントローラー。
 * チームスコープのゲーミフィケーションユーザー設定取得・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/settings")
@RequiredArgsConstructor
public class GamificationSettingController {

    private final GamificationSettingService gamificationSettingService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * 自分のゲーミフィケーション設定を取得する。
     *
     * @param teamId チームID
     * @return ユーザーゲーミフィケーション設定
     */
    @GetMapping("/me")
    public ApiResponse<GamificationUserSettingResponse> getMySetting(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toSettingResponse(
                        gamificationSettingService.getMySetting(userId, "TEAM", teamId).getData()
                )
        );
    }

    /**
     * 自分のゲーミフィケーション設定を更新する。
     *
     * @param teamId  チームID
     * @param request 更新リクエスト
     * @return 更新後のユーザーゲーミフィケーション設定
     */
    @PutMapping("/me")
    public ApiResponse<GamificationUserSettingResponse> updateMySetting(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateGamificationUserSettingRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toSettingResponse(
                        gamificationSettingService.updateMySetting(
                                userId, "TEAM", teamId,
                                request.showInRanking(),
                                request.showBadges()
                        ).getData()
                )
        );
    }
}
