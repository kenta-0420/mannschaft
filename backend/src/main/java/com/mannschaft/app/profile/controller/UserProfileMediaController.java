package com.mannschaft.app.profile.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.profile.ProfileMediaRole;
import com.mannschaft.app.profile.ProfileMediaScope;
import com.mannschaft.app.profile.dto.ProfileMediaCommitRequest;
import com.mannschaft.app.profile.dto.ProfileMediaResponse;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlRequest;
import com.mannschaft.app.profile.dto.ProfileMediaUploadUrlResponse;
import com.mannschaft.app.profile.service.ProfileMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ユーザープロフィールメディアコントローラー。
 * F01.6 ユーザーアイコン・バナー管理のAPIを提供する。
 *
 * <ul>
 *   <li>POST /api/v1/users/me/profile-media/{role}/upload-url — アップロードURL発行</li>
 *   <li>PUT  /api/v1/users/me/profile-media/{role}            — コミット（DB更新）</li>
 *   <li>DELETE /api/v1/users/me/profile-media/{role}          — メディア削除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users/me/profile-media")
@Tag(name = "プロフィールメディア", description = "F01.6 アイコン・バナー管理")
@RequiredArgsConstructor
public class UserProfileMediaController {

    private final ProfileMediaService profileMediaService;

    /**
     * ユーザープロフィールメディアのアップロード URL を発行する。
     *
     * @param role    メディアロール文字列（"icon" または "banner"）
     * @param request リクエスト DTO（contentType, fileSize）
     * @return Presigned PUT URL レスポンス
     */
    @PostMapping("/{role}/upload-url")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "ユーザープロフィールメディアアップロードURL発行")
    public ResponseEntity<ApiResponse<ProfileMediaUploadUrlResponse>> generateUploadUrl(
            @PathVariable String role,
            @RequestBody @Valid ProfileMediaUploadUrlRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        ProfileMediaRole mediaRole = parseRole(role);
        ProfileMediaUploadUrlResponse response =
                profileMediaService.generateUploadUrl(ProfileMediaScope.USER, userId, mediaRole, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アップロード完了後にユーザープロフィールメディアを DB に反映する。
     *
     * @param role    メディアロール文字列（"icon" または "banner"）
     * @param request コミットリクエスト DTO（r2Key を含む）
     * @return 更新後のプロフィールメディア情報
     */
    @PutMapping("/{role}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "ユーザープロフィールメディアコミット（DB更新）")
    public ResponseEntity<ApiResponse<ProfileMediaResponse>> commit(
            @PathVariable String role,
            @RequestBody @Valid ProfileMediaCommitRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        ProfileMediaRole mediaRole = parseRole(role);
        ProfileMediaResponse response =
                profileMediaService.commit(ProfileMediaScope.USER, userId, mediaRole, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ユーザープロフィールメディアを削除する。
     *
     * @param role メディアロール文字列（"icon" または "banner"）
     * @return 204 No Content
     */
    @DeleteMapping("/{role}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "ユーザープロフィールメディア削除")
    public ResponseEntity<Void> delete(@PathVariable String role) {
        Long userId = SecurityUtils.getCurrentUserId();
        ProfileMediaRole mediaRole = parseRole(role);
        profileMediaService.delete(ProfileMediaScope.USER, userId, mediaRole, userId);
        return ResponseEntity.noContent().build();
    }

    // ==================== プライベートメソッド ====================

    /**
     * パスパラメータのロール文字列を {@link ProfileMediaRole} に変換する。
     * "icon" → ICON、"banner" → BANNER。それ以外は 400 Bad Request をスローする。
     *
     * @param role ロール文字列
     * @return ProfileMediaRole
     * @throws ResponseStatusException ロールが不正な場合
     */
    private ProfileMediaRole parseRole(String role) {
        return switch (role.toLowerCase()) {
            case "icon"   -> ProfileMediaRole.ICON;
            case "banner" -> ProfileMediaRole.BANNER;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "role は 'icon' または 'banner' を指定してください");
        };
    }
}
