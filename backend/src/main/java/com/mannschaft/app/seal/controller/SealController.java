package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.service.SealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p>認可: 印鑑は本人専用リソースのため、全ハンドラでパスの {@code userId} と
 * ログインユーザーIDを突合する。不一致は 403（{@code COMMON_002}）、未認証は 401（{@code COMMON_000}）。</p>
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/seals")
@Tag(name = "電子印鑑", description = "F05.3 電子印鑑管理")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SealController {

    private final SealService sealService;

    /**
     * パスの userId とログインユーザーが一致することを検証する。
     * 未認証時は {@link SecurityUtils#getCurrentUserId()} が COMMON_000（401）を投げる。
     *
     * @param pathUserId パスの userId
     * @throws BusinessException 他人のリソースアクセス時（COMMON_002 → 403）
     */
    private void checkOwner(Long pathUserId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!currentUserId.equals(pathUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ユーザーの印鑑一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "印鑑一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SealResponse>>> listSeals(
            @PathVariable Long userId) {
        checkOwner(userId);
        List<SealResponse> seals = sealService.listSeals(userId);
        return ResponseEntity.ok(ApiResponse.of(seals));
    }

    /**
     * ユーザーのスコープ別デフォルト設定一覧を取得する。
     *
     * <p>PathPattern はリテラルセグメント "scope-defaults" を変数 {@code /{sealId}}(Long) より
     * 優先するため、両者は衝突しない（"scope-defaults" を Long 変換しようとして 400 になる退行を解消）。</p>
     */
    @GetMapping("/scope-defaults")
    @Operation(summary = "スコープデフォルト一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScopeDefaultResponse>>> getScopeDefaults(
            @PathVariable Long userId) {
        checkOwner(userId);
        List<ScopeDefaultResponse> defaults = sealService.listScopeDefaults(userId);
        return ResponseEntity.ok(ApiResponse.of(defaults));
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
        checkOwner(userId);
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
        checkOwner(userId);
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
        checkOwner(userId);
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
        checkOwner(userId);
        sealService.deleteSeal(userId, sealId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ユーザーの全印鑑を再生成する。
     */
    @PostMapping("/regenerate")
    @Operation(summary = "印鑑一括再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<List<SealResponse>>> regenerateSeals(
            @PathVariable Long userId) {
        checkOwner(userId);
        List<SealResponse> seals = sealService.regenerateSeals(userId);
        return ResponseEntity.ok(ApiResponse.of(seals));
    }
}
