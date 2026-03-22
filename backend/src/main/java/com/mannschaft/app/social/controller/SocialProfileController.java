package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.social.dto.CreateProfileRequest;
import com.mannschaft.app.social.dto.ProfileResponse;
import com.mannschaft.app.social.dto.UpdateProfileRequest;
import com.mannschaft.app.social.service.SocialProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ソーシャルプロフィールコントローラー。プロフィールのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/social/profiles")
@Tag(name = "ソーシャルプロフィール", description = "F04.4 ソーシャルプロフィール管理")
@RequiredArgsConstructor
public class SocialProfileController {

    private final SocialProfileService profileService;


    /**
     * ソーシャルプロフィールを作成する。
     */
    @PostMapping
    @Operation(summary = "プロフィール作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ProfileResponse>> createProfile(
            @Valid @RequestBody CreateProfileRequest request) {
        ProfileResponse response = profileService.createProfile(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自分のソーシャルプロフィールを取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "自分のプロフィール取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ProfileResponse>> getMyProfile() {
        ProfileResponse response = profileService.getMyProfile(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ソーシャルプロフィールを更新する。
     */
    @PatchMapping("/me")
    @Operation(summary = "プロフィール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        ProfileResponse response = profileService.updateProfile(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ハンドルでプロフィールを取得する。
     */
    @GetMapping("/handle/{handle}")
    @Operation(summary = "ハンドルでプロフィール取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfileByHandle(
            @PathVariable String handle) {
        ProfileResponse response = profileService.getProfileByHandle(handle);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ユーザーIDでプロフィールを取得する。
     */
    @GetMapping("/users/{userId}")
    @Operation(summary = "ユーザーIDでプロフィール取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfileByUserId(
            @PathVariable Long userId) {
        ProfileResponse response = profileService.getProfileByUserId(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ソーシャルプロフィールを無効化する。
     */
    @DeleteMapping("/me")
    @Operation(summary = "プロフィール無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateProfile() {
        profileService.deactivateProfile(SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
