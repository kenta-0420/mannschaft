package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.FollowListVisibility;
import com.mannschaft.app.social.dto.FollowListVisibilityResponse;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.dto.UpdateFollowListVisibilityRequest;
import com.mannschaft.app.social.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ユーザーフォロー可視化コントローラー。
 * F04.4 / F01.7 Phase 2: 他ユーザーのフォロー中・フォロワー一覧取得および公開設定変更APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "ユーザーフォロー可視化", description = "F04.4 / F01.7 Phase 2 フォロー関係の可視化")
@RequiredArgsConstructor
public class UserFollowController {

    private final FollowService followService;

    /**
     * 他ユーザーのフォロー中一覧を取得する。
     * 対象ユーザーの公開設定に応じてアクセス制御を行う。
     */
    @GetMapping("/{userId}/following")
    @Operation(summary = "他ユーザーのフォロー中一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "閲覧権限なし（非公開設定）")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getUserFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int size) {
        Long requesterId = SecurityUtils.getCurrentUserId();
        List<FollowResponse> following = followService.getUserFollowing(userId, requesterId, size);
        return ResponseEntity.ok(ApiResponse.of(following));
    }

    /**
     * 他ユーザーのフォロワー一覧を取得する。
     * 対象ユーザーの公開設定に応じてアクセス制御を行う。
     */
    @GetMapping("/{userId}/followers")
    @Operation(summary = "他ユーザーのフォロワー一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "閲覧権限なし（非公開設定）")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getUserFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int size) {
        Long requesterId = SecurityUtils.getCurrentUserId();
        List<FollowResponse> followers = followService.getUserFollowers(userId, requesterId, size);
        return ResponseEntity.ok(ApiResponse.of(followers));
    }

    /**
     * 自分がフォローしているチーム一覧を取得する。
     */
    @GetMapping("/me/followed-teams")
    @Operation(summary = "自分がフォローしているチーム一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getFollowedTeams(
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<FollowResponse> teams = followService.getFollowedTeams(userId, size);
        return ResponseEntity.ok(ApiResponse.of(teams));
    }

    /**
     * フォロー一覧の公開設定を取得する。
     */
    @GetMapping("/me/follow-list-visibility")
    @Operation(summary = "フォロー一覧公開設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FollowListVisibilityResponse>> getFollowListVisibility() {
        Long userId = SecurityUtils.getCurrentUserId();
        FollowListVisibility visibility = followService.getFollowListVisibility(userId);
        return ResponseEntity.ok(ApiResponse.of(new FollowListVisibilityResponse(visibility.name())));
    }

    /**
     * フォロー一覧の公開設定を更新する。
     */
    @PutMapping("/me/follow-list-visibility")
    @Operation(summary = "フォロー一覧公開設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "更新成功")
    public ResponseEntity<Void> updateFollowListVisibility(
            @Valid @RequestBody UpdateFollowListVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        FollowListVisibility visibility = FollowListVisibility.valueOf(request.getVisibility());
        followService.updateFollowListVisibility(userId, visibility);
        return ResponseEntity.noContent().build();
    }
}
