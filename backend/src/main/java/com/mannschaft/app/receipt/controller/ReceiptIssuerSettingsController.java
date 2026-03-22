package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.UpdateIssuerSettingsRequest;
import com.mannschaft.app.receipt.service.ReceiptIssuerSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 領収書発行者設定コントローラー。発行者設定のCRUD APIを提供する。
 * <p>
 * エンドポイント数: 4
 * <ul>
 *   <li>GET  /api/v1/admin/receipt-settings</li>
 *   <li>PUT  /api/v1/admin/receipt-settings</li>
 *   <li>POST /api/v1/admin/receipt-settings/logo</li>
 *   <li>DELETE /api/v1/admin/receipt-settings/logo</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/receipt-settings")
@Tag(name = "領収書発行者設定", description = "F08.4 領収書発行者設定CRUD")
@RequiredArgsConstructor
public class ReceiptIssuerSettingsController {

    private final ReceiptIssuerSettingsService issuerSettingsService;


    /**
     * 発行者設定を取得する。
     */
    @GetMapping
    @Operation(summary = "発行者設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<IssuerSettingsResponse>> getSettings(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        IssuerSettingsResponse response = issuerSettingsService.getSettings(type, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 発行者設定を作成または更新する（UPSERT）。
     */
    @PutMapping
    @Operation(summary = "発行者設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<IssuerSettingsResponse>> upsertSettings(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody UpdateIssuerSettingsRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        IssuerSettingsResponse response = issuerSettingsService.upsertSettings(type, scopeId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ロゴ画像をアップロードする。
     */
    @PostMapping("/logo")
    @Operation(summary = "ロゴ画像アップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アップロード成功")
    public ResponseEntity<ApiResponse<IssuerSettingsResponse>> uploadLogo(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        // TODO: MultipartFile のアップロード処理 + S3 へのアップロード + リサイズ
        String logoStorageKey = "receipt-logos/" + scopeType + "/" + scopeId + "/logo.png";
        IssuerSettingsResponse response = issuerSettingsService.updateLogo(type, scopeId, logoStorageKey);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ロゴ画像を削除する。
     */
    @DeleteMapping("/logo")
    @Operation(summary = "ロゴ画像削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteLogo(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        issuerSettingsService.deleteLogo(type, scopeId);
        return ResponseEntity.noContent().build();
    }
}
