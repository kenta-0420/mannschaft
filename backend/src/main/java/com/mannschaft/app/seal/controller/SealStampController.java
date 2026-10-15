package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 押印コントローラー。押印の実行・取消・検証・スコープデフォルト設定APIを提供する。
 *
 * <p>認可: 押印は本人専用操作のため、原則パスの {@code userId} とログインユーザーIDを
 * 突合する（不一致は 403 = {@code COMMON_002}、未認証は 401 = {@code COMMON_000}）。
 * 例外は押印検証 {@code verifyStamp}: 設計書 F05.3 では「押印者本人 or 対象ドキュメントの ADMIN」
 * 用途であり本人限定ではないため、本人突合は課さず認証のみ要求する（過剰認可の回避）。</p>
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/stamps")
@Tag(name = "押印管理", description = "F05.3 押印実行・取消・検証")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SealStampController {

    private final SealStampService stampService;
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
     * ユーザーの押印ログ一覧を取得する（カーソルベースページネーション・stampedAt 降順）。
     *
     * @param userId         パスのユーザーID（本人のみ）
     * @param cursor         カーソル（直前ページ末尾の id）。null の場合は先頭から
     * @param size           取得件数（既定 20・最大 50）。FE は ?cursor=&size= を送るため param 名は size
     * @param targetType     対象種別フィルタ（任意）
     * @param includeRevoked 取消済みを含めるか（既定 true）
     */
    @GetMapping
    @Operation(summary = "押印ログ一覧（カーソルページング）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<StampLogResponse>> listStamps(
            @PathVariable Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false, defaultValue = "true") boolean includeRevoked) {
        checkOwner(userId);
        CursorPagedResponse<StampLogResponse> response =
                stampService.listStampLogs(userId, cursor, size, targetType, includeRevoked);
        return ResponseEntity.ok(response);
    }

    /**
     * 押印を実行する。
     */
    @PostMapping
    @Operation(summary = "押印実行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "押印成功")
    public ResponseEntity<ApiResponse<StampLogResponse>> stamp(
            @PathVariable Long userId,
            @Valid @RequestBody StampRequest request) {
        checkOwner(userId);
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
        checkOwner(userId);
        StampLogResponse response = stampService.revokeStamp(userId, stampLogId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 押印を検証する。
     *
     * <p>設計書 F05.3 では verify の用途は「押印者本人 or 対象ドキュメントの ADMIN」であり
     * 本人限定ではない（第三者による検証用途）。現状 ADMIN 判定基盤が seal ドメインに無いため、
     * 過剰認可を避け、本人突合は課さず認証済みであることのみ要求する。</p>
     */
    @GetMapping("/{stampLogId}/verify")
    @Operation(summary = "押印検証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検証成功")
    public ResponseEntity<ApiResponse<StampVerifyResponse>> verifyStamp(
            @PathVariable Long userId,
            @PathVariable Long stampLogId) {
        // 認証必須（クラスレベル @PreAuthorize）。本人突合は設計書に基づき課さない。
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
        checkOwner(userId);
        ScopeDefaultResponse response = sealService.setScopeDefault(userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
