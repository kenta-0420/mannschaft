package com.mannschaft.app.safetycheck.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.safetycheck.dto.FollowupUpdateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyFollowupResponse;
import com.mannschaft.app.safetycheck.service.SafetyFollowupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * フォローアップコントローラー。フォローアップの更新APIを提供する。
 *
 * <p>認可は {@code SafetyFollowupService} で行う（フォローアップから親の安否確認を辿り、
 * entity 由来スコープの ADMIN/DEPUTY_ADMIN のみ許可。権限が無い場合は 404 で存在秘匿）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/safety-checks/followups")
@Tag(name = "安否確認フォローアップ管理", description = "F03.6 フォローアップ更新")
@RequiredArgsConstructor
public class SafetyFollowupController {

    private final SafetyFollowupService followupService;

    /**
     * フォローアップを更新する。
     */
    @PatchMapping("/{followupId}")
    @Operation(summary = "フォローアップ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SafetyFollowupResponse>> updateFollowup(
            @PathVariable Long followupId,
            @Valid @RequestBody FollowupUpdateRequest request) {
        SafetyFollowupResponse response = followupService.updateFollowup(
                followupId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
