package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.social.dto.FollowRequest;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * フォローコントローラー。フォロー・アンフォロー・一覧取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/social/follows")
@Tag(name = "フォロー管理", description = "F04.4 フォロー・フォロワー管理")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;


    /**
     * フォローする。
     */
    @PostMapping
    @Operation(summary = "フォロー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "フォロー成功")
    @SelfScopedEndpoint("FollowService#follow が作成する FollowEntity の followerId は常に "
            + "SecurityUtils.getCurrentUserId() のみを使う（リクエストの followedId は他人のデータへの"
            + "到達点ではなくフォロー対象の指定にすぎない）")
    public ResponseEntity<ApiResponse<FollowResponse>> follow(
            @Valid @RequestBody FollowRequest request) {
        FollowResponse response = followService.follow(
                request.getFollowedType(), request.getFollowedId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * アンフォローする。
     */
    @DeleteMapping
    @Operation(summary = "アンフォロー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "アンフォロー成功")
    @SelfScopedEndpoint("FollowService#unfollow の検索条件が (FollowerType.USER, "
            + "SecurityUtils.getCurrentUserId()) を follower 側キーとして固定するため、"
            + "他人のフォロー行には到達しない（FollowService.java:81-84）")
    public ResponseEntity<Void> unfollow(
            @RequestParam String followedType,
            @RequestParam Long followedId) {
        followService.unfollow(followedType, followedId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * フォロー一覧を取得する。
     */
    @GetMapping("/following")
    @Operation(summary = "フォロー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @SelfScopedEndpoint("FollowService#getFollowing の検索条件が (FollowerType.USER, "
            + "SecurityUtils.getCurrentUserId()) のみに束縛される（FollowService.java:98-103）")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getFollowing(
            @RequestParam(defaultValue = "20") int size) {
        List<FollowResponse> following = followService.getFollowing(SecurityUtils.getCurrentUserId(), size);
        return ResponseEntity.ok(ApiResponse.of(following));
    }

    /**
     * フォロワー一覧を取得する。
     */
    @GetMapping("/followers")
    @Operation(summary = "フォロワー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @SelfScopedEndpoint("FollowService#getFollowers の検索条件が (FollowedType.USER, "
            + "SecurityUtils.getCurrentUserId()) のみに束縛される（FollowService.java:112-117）")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getFollowers(
            @RequestParam(defaultValue = "20") int size) {
        List<FollowResponse> followers = followService.getFollowers(SecurityUtils.getCurrentUserId(), size);
        return ResponseEntity.ok(ApiResponse.of(followers));
    }

    /**
     * フォロー状態を確認する。
     */
    @GetMapping("/check")
    @Operation(summary = "フォロー状態確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認成功")
    @SelfScopedEndpoint("FollowService#isFollowing の検索条件が (FollowerType.USER, "
            + "SecurityUtils.getCurrentUserId()) を follower 側キーとして固定するため、判定結果は "
            + "認証ユーザー自身とfollowedIdの関係のみを表す真偽値であり、他人の保護されたデータは含まない")
    public ResponseEntity<ApiResponse<Boolean>> isFollowing(
            @RequestParam String followedType,
            @RequestParam Long followedId) {
        boolean following = followService.isFollowing(followedType, followedId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(following));
    }
}
