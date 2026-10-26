package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.village.dto.VillageNicknameResponse;
import com.mannschaft.app.village.dto.VillageNicknameUpdateRequest;
import com.mannschaft.app.village.service.VillageNicknameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * F17.1 B4 — 村ニックネーム（全村共通 1 つ）コントローラ。
 *
 * <p>設計書 §4.5 に従い、ユーザー単位の {@code /me} エンドポイントで操作する。
 * Phase 1 は村IDを取らない（全村共通）。Phase 2 で村ごと上書き API を別途追加予定。</p>
 */
@RestController
@RequestMapping("/api/v1/me/village-nickname")
@Tag(name = "村ニックネーム", description = "F17.1 Phase 1 村ニックネーム（全村共通 1 つ）")
@RequiredArgsConstructor
public class VillageNicknameController {

    private final VillageNicknameService nicknameService;

    /**
     * 自分の村ニックネームを取得する。
     * 未設定なら 200 OK + data:null を返す（404 にしない）。
     */
    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみで、"
            + "リクエストは他ユーザーの識別子を受け取らない"
            + "（VillageNicknameService#getMyNickname が認証主体の userId でのみ引く）")
    @GetMapping
    @Operation(summary = "自分の村ニックネーム取得（全村共通 1 つ）")
    public ResponseEntity<ApiResponse<VillageNicknameResponse>> getMyNickname() {
        Long userId = SecurityUtils.getCurrentUserId();
        Optional<VillageNicknameResponse> response = nicknameService.getMyNickname(userId);
        return ResponseEntity.ok(ApiResponse.of(response.orElse(null)));
    }

    /**
     * 自分の村ニックネームを更新（新規作成 or 上書き）する。
     * <ul>
     *   <li>409 NICKNAME_TAKEN: グローバル UNIQUE 衝突</li>
     *   <li>422 NICKNAME_INVALID: 長さ / 使用文字 / 禁止語</li>
     *   <li>429 NICKNAME_CHANGE_THROTTLED: 月3回超過</li>
     * </ul>
     */
    @SelfScopedEndpoint("更新対象のニックネーム行は SecurityUtils.getCurrentUserId() で解決され、"
            + "リクエストボディは他ユーザーの識別子を含まない"
            + "（VillageNicknameService#updateMyNickname が認証主体の行のみを upsert する）")
    @PutMapping
    @Operation(summary = "自分の村ニックネーム更新（全村共通 1 つ）")
    public ResponseEntity<ApiResponse<VillageNicknameResponse>> updateMyNickname(
            @Valid @RequestBody VillageNicknameUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageNicknameResponse response = nicknameService.updateMyNickname(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
