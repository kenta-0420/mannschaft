package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.service.SealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 電子印鑑コントローラー。印鑑のCRUD・スコープデフォルト設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/seals")
@Tag(name = "電子印鑑", description = "F05.3 電子印鑑管理")
@RequiredArgsConstructor
public class SealController {

    private final SealService sealService;

    /**
     * ユーザーの印鑑一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "印鑑一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SealResponse>>> listSeals(
            @PathVariable Long userId) {
        List<SealResponse> seals = sealService.listSeals(userId);
        return ResponseEntity.ok(ApiResponse.of(seals));
    }

    /**
     * 印鑑詳細を取得する。
     */
    @GetMapping("/{sealId}")
    @Operation(summary = "印鑑詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SealResponse>> getSeal(
            @PathVariable Long userId,
            @PathVariable Long sealId) {
        SealResponse response = sealService.getSeal(userId, sealId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 印鑑を作成する。
     */
    @PostMapping
    @Operation(summary = "印鑑作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SealResponse>> createSeal(
            @PathVariable Long userId,
            @Valid @RequestBody CreateSealRequest request) {
        SealResponse response = sealService.createSeal(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 印鑑を更新する。
     */
    @PutMapping("/{sealId}")
    @Operation(summary = "印鑑更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SealResponse>> updateSeal(
            @PathVariable Long userId,
            @PathVariable Long sealId,
            @Valid @RequestBody UpdateSealRequest request) {
        SealResponse response = sealService.updateSeal(userId, sealId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 印鑑を削除する。
     */
    @DeleteMapping("/{sealId}")
    @Operation(summary = "印鑑削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSeal(
            @PathVariable Long userId,
            @PathVariable Long sealId) {
        sealService.deleteSeal(userId, sealId);
        return ResponseEntity.noContent().build();
    }
}
