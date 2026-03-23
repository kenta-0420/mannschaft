package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.service.SealService;
import com.mannschaft.app.seal.service.SealStampService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 押印コントローラー。押印の実行・取消・検証・スコープデフォルト設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/stamps")
@Tag(name = "押印管理", description = "F05.3 押印実行・取消・検証")
@RequiredArgsConstructor
public class SealStampController {

    private final SealStampService stampService;
    private final SealService sealService;

    /**
     * 押印を実行する。
     */
    @PostMapping
    @Operation(summary = "押印実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "押印成功")
    public ResponseEntity<ApiResponse<StampLogResponse>> stamp(
            @PathVariable Long userId,
            @Valid @RequestBody StampRequest request) {
        StampLogResponse response = stampService.stamp(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 押印を取り消す。
     */
    @PostMapping("/{stampLogId}/revoke")
    @Operation(summary = "押印取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取消成功")
    public ResponseEntity<ApiResponse<StampLogResponse>> revokeStamp(
            @PathVariable Long userId,
            @PathVariable Long stampLogId) {
        StampLogResponse response = stampService.revokeStamp(userId, stampLogId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 押印を検証する。
     */
    @GetMapping("/{stampLogId}/verify")
    @Operation(summary = "押印検証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検証成功")
    public ResponseEntity<ApiResponse<StampVerifyResponse>> verifyStamp(
            @PathVariable Long userId,
            @PathVariable Long stampLogId) {
        StampVerifyResponse response = stampService.verifyStamp(stampLogId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スコープデフォルトを設定する。
     */
    @PostMapping("/scope-defaults")
    @Operation(summary = "スコープデフォルト設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<ScopeDefaultResponse>> setScopeDefault(
            @PathVariable Long userId,
            @Valid @RequestBody SetScopeDefaultRequest request) {
        ScopeDefaultResponse response = sealService.setScopeDefault(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
