package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.CreatePresetRequest;
import com.mannschaft.app.receipt.dto.PresetResponse;
import com.mannschaft.app.receipt.dto.UpdatePresetRequest;
import com.mannschaft.app.receipt.service.ReceiptPresetService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 領収書プリセットコントローラー。プリセットのCRUD APIを提供する。
 * <p>
 * エンドポイント数: 4
 * <ul>
 *   <li>GET    /api/v1/admin/receipt-presets       — プリセット一覧</li>
 *   <li>POST   /api/v1/admin/receipt-presets       — プリセット作成</li>
 *   <li>PUT    /api/v1/admin/receipt-presets/{id}  — プリセット更新</li>
 *   <li>DELETE /api/v1/admin/receipt-presets/{id}  — プリセット削除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/receipt-presets")
@Tag(name = "領収書プリセット", description = "F08.4 領収書プリセットCRUD")
@RequiredArgsConstructor
public class ReceiptPresetController {

    private final ReceiptPresetService presetService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * プリセット一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "プリセット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PresetResponse>>> listPresets(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        List<PresetResponse> presets = presetService.listPresets(type, scopeId);
        return ResponseEntity.ok(ApiResponse.of(presets));
    }

    /**
     * プリセットを作成する。
     */
    @PostMapping
    @Operation(summary = "プリセット作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PresetResponse>> createPreset(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody CreatePresetRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        PresetResponse response = presetService.createPreset(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * プリセットを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "プリセット更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PresetResponse>> updatePreset(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id,
            @Valid @RequestBody UpdatePresetRequest request) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        PresetResponse response = presetService.updatePreset(type, scopeId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プリセットを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "プリセット削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePreset(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @PathVariable Long id) {
        ReceiptScopeType type = ReceiptScopeType.valueOf(scopeType.toUpperCase());
        presetService.deletePreset(type, scopeId, id);
        return ResponseEntity.noContent().build();
    }
}
