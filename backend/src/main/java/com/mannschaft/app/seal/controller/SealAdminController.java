package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.seal.dto.AdminRegenerateResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.service.SealAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 電子印鑑管理者コントローラー。SYSTEM_ADMIN向けの一括操作APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/seals")
@Tag(name = "電子印鑑管理（管理者）", description = "F05.3 管理者用一括操作")
@RequiredArgsConstructor
public class SealAdminController {

    private final SealAdminService sealAdminService;
    private final AccessControlService accessControlService;

    /**
     * 全印鑑一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "全印鑑一覧（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SealResponse>>> listAllSeals() {
        // 認可根治 Wave5: scope 引数を持たない全テナント横断 API のため SYSTEM_ADMIN 限定
        // （SecurityConfig のパス単位 hasRole と二重防御）。
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        List<SealResponse> seals = sealAdminService.listAllSeals();
        return ResponseEntity.ok(ApiResponse.of(seals));
    }

    /**
     * 全印鑑のSVGを一括再生成する。
     */
    @PostMapping("/regenerate")
    @Operation(summary = "一括再生成（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<AdminRegenerateResponse>> regenerateAll() {
        // 認可根治 Wave5: 全ユーザーの印鑑を書き換える破壊的一括操作のため SYSTEM_ADMIN 限定。
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        AdminRegenerateResponse response = sealAdminService.regenerateAll();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
