package com.mannschaft.app.digest.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.digest.dto.DigestConfigRequest;
import com.mannschaft.app.digest.dto.DigestConfigResponse;
import com.mannschaft.app.digest.service.DigestConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムラインダイジェスト自動生成設定コントローラー。
 * 設定の取得・作成更新・無効化エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline-digest/config")
@Tag(name = "タイムラインダイジェスト設定")
@RequiredArgsConstructor
public class DigestConfigController {

    private final DigestConfigService digestConfigService;

    /**
     * 自動生成設定の取得。
     */
    @GetMapping
    @Operation(summary = "自動生成設定の取得")
    public ResponseEntity<ApiResponse<DigestConfigResponse>> getConfig(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        DigestConfigResponse response = digestConfigService.getConfig(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自動生成設定の作成・更新。
     */
    @PutMapping
    @Operation(summary = "自動生成設定の作成・更新")
    public ResponseEntity<ApiResponse<DigestConfigResponse>> createOrUpdateConfig(
            @Valid @RequestBody DigestConfigRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DigestConfigService.ConfigSaveResult result = digestConfigService.createOrUpdateConfig(request, userId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.of(result.response()));
    }

    /**
     * 自動生成設定の無効化（論理削除）。
     */
    @DeleteMapping
    @Operation(summary = "自動生成設定の無効化")
    public ResponseEntity<Void> deleteConfig(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        digestConfigService.deleteConfig(scopeType, scopeId);
        return ResponseEntity.noContent().build();
    }
}
