package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.CreateUnflagRequest;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.service.YabaiUnflagService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ヤバいやつ解除コントローラー（ユーザー用）。解除申請・状態確認APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/yabai")
@Tag(name = "ヤバいやつ解除", description = "F10.2 ヤバいやつ解除申請")
@RequiredArgsConstructor
public class YabaiController {

    private final YabaiUnflagService unflagService;


    /**
     * ヤバいやつ解除申請を作成する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * 申請者は常に {@code SecurityUtils.getCurrentUserId()} が渡され、
     * リクエストボディで申請者本人を偽装する経路が構造的に無い
     * （YabaiController#createUnflagRequest）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "unflagService.createUnflagRequest(userId, reason) の userId は"
                    + "SecurityUtils.getCurrentUserId() のみで束縛される"
                    + "（YabaiController#createUnflagRequest）")
    @PostMapping("/unflag-request")
    @Operation(summary = "ヤバいやつ解除申請")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "申請成功")
    public ResponseEntity<ApiResponse<YabaiUnflagResponse>> createUnflagRequest(
            @Valid @RequestBody CreateUnflagRequest request) {
        YabaiUnflagResponse response = unflagService.createUnflagRequest(SecurityUtils.getCurrentUserId(), request.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 解除申請状態を取得する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code unflagService.getLatestRequestStatus} は
     * {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、
     * 他人の解除申請状態へ到達する経路が構造的に無い
     * （YabaiController#getUnflagRequestStatus）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "unflagService.getLatestRequestStatus(userId) は SecurityUtils.getCurrentUserId() の"
                    + "みを検索条件に渡す（YabaiController#getUnflagRequestStatus）")
    @GetMapping("/unflag-request/status")
    @Operation(summary = "解除申請状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<YabaiUnflagResponse>> getUnflagRequestStatus() {
        YabaiUnflagResponse response = unflagService.getLatestRequestStatus(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
