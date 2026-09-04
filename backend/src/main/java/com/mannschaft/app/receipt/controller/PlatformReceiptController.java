package com.mannschaft.app.receipt.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.PageResponse;
import com.mannschaft.app.receipt.dto.PlatformReceiptSummaryResponse;
import com.mannschaft.app.receipt.dto.UpdateIssuerSettingsRequest;
import com.mannschaft.app.receipt.service.PlatformReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 運営領収書コンソール（F08.12 §4.1）。運営（プラットフォーム事業者）自身が発行者となる
 * 領収書・適格請求書を SYSTEM_ADMIN が管理する。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code /api/v1/system-admin/**} 配下にあり、
 * {@code SecurityConfig} の {@code requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")}
 * によりフィルタチェーンで SYSTEM_ADMIN へ宣言的に予約されている。</p>
 *
 * <p>加えて Service 層でも {@code checkAdminOrAboveIncludingPlatform} を通す。これは
 * バッチ・イベントリスナーといったフィルタチェーンを経ない経路への二重防御であり、
 * とりわけ<b>非 SYSTEM_ADMIN が PLATFORM スコープを指したときに 500 ではなく 403 になる</b>
 * ことを保証する（F08.12 §2.1）。パス定義を変更・削除する際は本注釈の根拠が失効するため、
 * 必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin")
@Tag(name = "運営領収書", description = "F08.12 運営領収書・適格請求書発行（PLATFORM スコープ）")
@RequiredArgsConstructor
public class PlatformReceiptController {

    private final PlatformReceiptService platformReceiptService;

    /**
     * PLATFORM 発行者設定を取得する。未登録でも 404 にせず既定値を返す。
     */
    @GetMapping("/receipt-settings")
    @Operation(summary = "運営の発行者設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<IssuerSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.of(
                platformReceiptService.getPlatformSettings(SecurityUtils.getCurrentUserId())));
    }

    /**
     * PLATFORM 発行者設定を更新する（未作成なら作成する UPSERT）。
     */
    @PutMapping("/receipt-settings")
    @Operation(summary = "運営の発行者設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<IssuerSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateIssuerSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                platformReceiptService.updatePlatformSettings(SecurityUtils.getCurrentUserId(), request)));
    }

    /**
     * 運営領収書の一覧を取得する。0 件でも 200 + 空配列を返す（404 にしない）。
     */
    @GetMapping("/receipts")
    @Operation(summary = "運営領収書一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PageResponse<PlatformReceiptSummaryResponse>>> listReceipts(
            @RequestParam(name = "include_voided", defaultValue = "false") boolean includeVoided,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.of(platformReceiptService.listReceipts(
                SecurityUtils.getCurrentUserId(), includeVoided, page, size)));
    }
}
