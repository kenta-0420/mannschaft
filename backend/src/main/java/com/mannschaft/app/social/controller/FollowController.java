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

/**
 * フォローコントローラー。フォロー・アンフォロー・一覧取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/social/follows")
@Tag(name = "フォロー管理", description = "F04.4 フォロー・フォロワー管理")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * フォローする。
     */
    @PostMapping
    @Operation(summary = "フォロー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "フォロー成功")
    public ResponseEntity<ApiResponse<FollowResponse>> follow(
            @Valid @RequestBody FollowRequest request) {
        FollowResponse response = followService.follow(
                request.getFollowedType(), request.getFollowedId(), getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * アンフォローする。
     */
    @DeleteMapping
    @Operation(summary = "アンフォロー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "アンフォロー成功")
    public ResponseEntity<Void> unfollow(
            @RequestParam String followedType,
            @RequestParam Long followedId) {
        followService.unfollow(followedType, followedId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * フォロー一覧を取得する。
     */
    @GetMapping("/following")
    @Operation(summary = "フォロー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getFollowing(
            @RequestParam(defaultValue = "20") int size) {
        List<FollowResponse> following = followService.getFollowing(getCurrentUserId(), size);
        return ResponseEntity.ok(ApiResponse.of(following));
    }

    /**
     * フォロワー一覧を取得する。
     */
    @GetMapping("/followers")
    @Operation(summary = "フォロワー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FollowResponse>>> getFollowers(
            @RequestParam(defaultValue = "20") int size) {
        List<FollowResponse> followers = followService.getFollowers(getCurrentUserId(), size);
        return ResponseEntity.ok(ApiResponse.of(followers));
    }

    /**
     * フォロー状態を確認する。
     */
    @GetMapping("/check")
    @Operation(summary = "フォロー状態確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認成功")
    public ResponseEntity<ApiResponse<Boolean>> isFollowing(
            @RequestParam String followedType,
            @RequestParam Long followedId) {
        boolean following = followService.isFollowing(followedType, followedId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(following));
    }
}
