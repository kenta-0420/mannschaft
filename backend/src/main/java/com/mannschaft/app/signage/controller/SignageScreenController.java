package com.mannschaft.app.signage.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.signage.service.SignageAccessTokenService;
import com.mannschaft.app.signage.service.SignageAccessTokenService.IssueSignageTokenRequest;
import com.mannschaft.app.signage.service.SignageAccessTokenService.SignageAccessTokenResponse;
import com.mannschaft.app.signage.service.SignageEmergencyService;
import com.mannschaft.app.signage.service.SignageEmergencyService.BroadcastEmergencyRequest;
import com.mannschaft.app.signage.service.SignageEmergencyService.EmergencyMessageResponse;
import com.mannschaft.app.signage.service.SignageScreenService;
import com.mannschaft.app.signage.service.SignageScreenService.CreateSignageScreenRequest;
import com.mannschaft.app.signage.service.SignageScreenService.SignageScreenResponse;
import com.mannschaft.app.signage.service.SignageScreenService.UpdateSignageScreenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * デジタルサイネージ 画面管理コントローラー。
 * 画面の作成・一覧・取得・更新・削除、トークン管理、緊急メッセージを提供する。
 */
@RestController
@RequestMapping("/api/signage/screens")
@RequiredArgsConstructor
public class SignageScreenController {

    private final SignageScreenService screenService;
    private final SignageAccessTokenService tokenService;
    private final SignageEmergencyService emergencyService;

    // ========================================
    // 画面管理
    // ========================================

    /**
     * 画面を作成する。
     * 認可: 認証済みユーザー
     * レスポンス: 201 Created
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SignageScreenResponse>> createScreen(
            @RequestBody CreateSignageScreenRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SignageScreenResponse response = screenService.createScreen(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スコープに紐づく画面一覧を取得する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @GetMapping
    public ApiResponse<List<SignageScreenResponse>> listScreens(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        return ApiResponse.of(screenService.listScreens(scopeType, scopeId));
    }

    /**
     * 指定IDの画面を取得する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @GetMapping("/{id}")
    public ApiResponse<SignageScreenResponse> getScreen(@PathVariable Long id) {
        return ApiResponse.of(screenService.getScreen(id));
    }

    /**
     * 画面を更新する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @PutMapping("/{id}")
    public ApiResponse<SignageScreenResponse> updateScreen(
            @PathVariable Long id,
            @RequestBody UpdateSignageScreenRequest request) {
        return ApiResponse.of(screenService.updateScreen(id, request));
    }

    /**
     * 画面を論理削除する。
     * 認可: 認証済みユーザー
     * レスポンス: 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteScreen(@PathVariable Long id) {
        screenService.deleteScreen(id);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // トークン管理
    // ========================================

    /**
     * アクセストークンを発行する。
     * 認可: 認証済みユーザー
     * レスポンス: 201 Created
     */
    @PostMapping("/{id}/tokens")
    public ResponseEntity<ApiResponse<SignageAccessTokenResponse>> issueToken(
            @PathVariable Long id,
            @RequestBody IssueSignageTokenRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        SignageAccessTokenResponse response = tokenService.issueToken(id, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 画面に紐づくトークン一覧を取得する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @GetMapping("/{id}/tokens")
    public ApiResponse<List<SignageAccessTokenResponse>> listTokens(@PathVariable Long id) {
        return ApiResponse.of(tokenService.listTokens(id));
    }

    /**
     * トークンを無効化する。
     * 認可: 認証済みユーザー
     * レスポンス: 204 No Content
     */
    @DeleteMapping("/tokens/{tokenId}")
    public ResponseEntity<Void> revokeToken(@PathVariable Long tokenId) {
        tokenService.revokeToken(tokenId);
        return ResponseEntity.noContent().build();
    }

    // ========================================
    // 緊急メッセージ
    // ========================================

    /**
     * 緊急メッセージをブロードキャストする。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @PostMapping("/{id}/emergency")
    public ApiResponse<EmergencyMessageResponse> broadcastEmergency(
            @PathVariable Long id,
            @RequestBody BroadcastEmergencyRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(emergencyService.broadcastEmergency(id, userId, request));
    }

    /**
     * 緊急メッセージ履歴一覧を取得する。
     * 認可: 認証済みユーザー
     * レスポンス: 200 OK
     */
    @GetMapping("/{id}/emergency")
    public ApiResponse<List<EmergencyMessageResponse>> listEmergencyMessages(@PathVariable Long id) {
        return ApiResponse.of(emergencyService.listEmergencyMessages(id));
    }
}
