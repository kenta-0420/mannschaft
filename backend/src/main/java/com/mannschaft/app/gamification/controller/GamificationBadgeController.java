package com.mannschaft.app.gamification.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.gamification.GamificationMapper;
import com.mannschaft.app.gamification.dto.AwardBadgeRequest;
import com.mannschaft.app.gamification.dto.BadgeResponse;
import com.mannschaft.app.gamification.dto.CreateBadgeRequest;
import com.mannschaft.app.gamification.dto.UpdateBadgeRequest;
import com.mannschaft.app.gamification.service.GamificationBadgeService;
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
 * ゲーミフィケーション・バッジコントローラー。
 * チームスコープのバッジ管理・取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/gamification/badges")
@RequiredArgsConstructor
public class GamificationBadgeController {

    private final GamificationBadgeService gamificationBadgeService;
    private final GamificationMapper gamificationMapper;
    private final AccessControlService accessControlService;

    /**
     * バッジ一覧を取得する。
     *
     * @param teamId チームID
     * @return バッジ一覧
     */
    @GetMapping
    public ApiResponse<List<BadgeResponse>> list(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        List<BadgeResponse> responses = gamificationBadgeService
                .getBadges("TEAM", teamId)
                .getData()
                .stream()
                .map(gamificationMapper::toBadgeResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * バッジを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成されたバッジ
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BadgeResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateBadgeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(
                gamificationMapper.toBadgeResponse(
                        gamificationBadgeService.createBadge(
                                "TEAM", teamId,
                                request.name(),
                                request.badgeType(),
                                request.conditionType(),
                                request.conditionValue(),
                                request.conditionPeriod(),
                                request.iconEmoji(),
                                request.isRepeatable()
                        ).getData()
                )
        );
    }

    /**
     * バッジを更新する。
     *
     * @param teamId  チームID
     * @param id      バッジID
     * @param request 更新リクエスト
     * @return 更新後のバッジ
     */
    @PutMapping("/{id}")
    public ApiResponse<BadgeResponse> update(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateBadgeRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        GamificationBadgeService.UpdateBadgeParams params = new GamificationBadgeService.UpdateBadgeParams(
                request.name(),
                null,
                null,
                null,
                null,
                null,
                request.isActive(),
                request.version()
        );
        return ApiResponse.of(
                gamificationMapper.toBadgeResponse(
                        gamificationBadgeService.updateBadge(id, "TEAM", teamId, params).getData()
                )
        );
    }

    /**
     * バッジを削除する。
     *
     * @param teamId チームID
     * @param id     バッジID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        gamificationBadgeService.deleteBadge(id, "TEAM", teamId);
    }

    /**
     * バッジを手動付与する。
     *
     * @param teamId  チームID
     * @param id      バッジID
     * @param request 付与リクエスト
     */
    @PostMapping("/{id}/award")
    @ResponseStatus(HttpStatus.CREATED)
    public void award(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody AwardBadgeRequest request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(adminId, teamId, "TEAM");
        gamificationBadgeService.awardBadgeManually(id, request.userId(), "TEAM", teamId, adminId);
    }

    /**
     * 自分が取得済みのバッジ一覧を取得する。
     *
     * @param teamId チームID
     * @return 取得済みバッジ一覧
     */
    @GetMapping("/me")
    public ApiResponse<List<BadgeResponse>> getMyBadges(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, teamId, "TEAM");
        List<BadgeResponse> responses = gamificationBadgeService
                .getUserBadges(userId, "TEAM", teamId)
                .getData()
                .stream()
                .map(gamificationMapper::toBadgeResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * 指定ユーザーが取得済みのバッジ一覧を取得する。
     *
     * @param teamId チームID
     * @param userId 対象ユーザーID
     * @return 取得済みバッジ一覧
     */
    @GetMapping("/users/{userId}")
    public ApiResponse<List<BadgeResponse>> getUserBadges(
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, teamId, "TEAM");
        List<BadgeResponse> responses = gamificationBadgeService
                .getUserBadges(userId, "TEAM", teamId)
                .getData()
                .stream()
                .map(gamificationMapper::toBadgeResponse)
                .toList();
        return ApiResponse.of(responses);
    }
}
