package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.dto.CreatePointRuleRequest;
import com.mannschaft.app.gamification.dto.PointRuleResponse;
import com.mannschaft.app.gamification.dto.UpdatePointRuleRequest;
import com.mannschaft.app.gamification.service.GamificationPointRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ゲーミフィケーション・ポイントルールコントローラー。
 * チームスコープのポイントルール管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/point-rules")
@RequiredArgsConstructor
public class GamificationPointRuleController {

    private final GamificationPointRuleService gamificationPointRuleService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * ポイントルール一覧を取得する。
     *
     * @param teamId チームID
     * @return ポイントルール一覧
     */
    @GetMapping
    public ApiResponse<List<PointRuleResponse>> list(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        List<PointRuleResponse> responses = gamificationPointRuleService
                .getRules("TEAM", teamId)
                .getData()
                .stream()
                .map(gamificationMapper::toPointRuleResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * ポイントルールを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成されたポイントルール
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PointRuleResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreatePointRuleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toPointRuleResponse(
                        gamificationPointRuleService.createRule(
                                "TEAM", teamId,
                                request.actionType(),
                                request.name(),
                                request.points(),
                                request.dailyLimit()
                        ).getData()
                )
        );
    }

    /**
     * ポイントルールを更新する。
     *
     * @param teamId  チームID
     * @param id      ポイントルールID
     * @param request 更新リクエスト
     * @return 更新後のポイントルール
     */
    @PutMapping("/{id}")
    public ApiResponse<PointRuleResponse> update(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdatePointRuleRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        int points = request.points() != null ? request.points() : 0;
        int dailyLimit = request.dailyLimit() != null ? request.dailyLimit() : 0;
        boolean isActive = request.isActive() != null ? request.isActive() : true;
        return ApiResponse.of(
                gamificationMapper.toPointRuleResponse(
                        gamificationPointRuleService.updateRule(
                                id, "TEAM", teamId,
                                points, dailyLimit, isActive, request.version()
                        ).getData()
                )
        );
    }

    /**
     * ポイントルールを削除する。
     *
     * @param teamId チームID
     * @param id     ポイントルールID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        gamificationPointRuleService.deleteRule(id, "TEAM", teamId);
    }
}
