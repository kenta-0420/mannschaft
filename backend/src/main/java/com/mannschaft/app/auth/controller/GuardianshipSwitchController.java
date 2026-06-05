package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 P3a 後見切替コントローラー（切替可能な子の一覧）。
 *
 * <p>認証ユーザー（保護者）が後見切替できる子・封印された子を取得する
 * {@code GET /api/v1/me/guardianship/switchable-children}（02_api_design §2.1）を提供する。</p>
 *
 * <p>閲覧者は常に {@code SecurityUtils.getCurrentUserId()}（自分）に固定し、
 * 他人の保護者一覧を覗く経路は提供しない（IDOR 防止・03_security §2/§3）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/guardianship")
@Tag(name = "後見切替")
@RequiredArgsConstructor
public class GuardianshipSwitchController {

    private final GuardianshipSwitchService guardianshipSwitchService;

    /**
     * 認証ユーザー（保護者）が後見切替できる子の一覧を取得する。
     * 保護者リンクはあるが年齢ポリシーで封印された子は {@code blockedChildren} に分離して返す。
     */
    @GetMapping("/switchable-children")
    @Operation(summary = "切替可能な子の一覧取得",
            description = "認証ユーザー（保護者）が後見切替できる子と、年齢到達で封印された子を取得する")
    public ResponseEntity<ApiResponse<SwitchableChildrenResponse>> getSwitchableChildren() {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        SwitchableChildrenResponse response =
                guardianshipSwitchService.listSwitchableChildren(guardianUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
