package com.mannschaft.app.common.pdf.verify;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部署名トークン検証 API（F12.1 §5.14 / F09.15 §9.4）。
 *
 * <p>v1 は ADMIN のみで簡素化。将来 v2 では「当該誓約の本人」も許可する予定。
 *
 * <p><strong>認可（認可根治 Phase 3-a / 2026-05-30）:</strong>
 * 本 EP はリクエストボディ（Base64 PDF + 署名トークン）のみを受け取り、
 * 特定のチーム・組織スコープを持たないプラットフォーム横断の検証操作である。
 * よって per-scope の {@code @accessGuard} では表現できないため、旧 {@code @PreAuthorize("hasRole('ADMIN')")}
 * から、スコープ不在の AttendanceBatch（全テナント横断バッチ）と同様に
 * {@link AccessControlService#checkSystemAdmin(Long)} で SYSTEM_ADMIN に限定して封鎖する
 *（旧「ADMIN のみ」から厳格化＝安全側。per-scope ADMIN への緩和が要件なら殿の判断で再調整）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pdf-signatures")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "PDF Signature Verify", description = "内部署名トークン検証 API（v1 SYSTEM_ADMIN 限定）")
public class PdfSignatureVerifyController {

    private final PdfSignatureVerifyService pdfSignatureVerifyService;
    private final AccessControlService accessControlService;

    /**
     * 送信された PDF（Base64）の内部署名を検証する。
     *
     * @param request 検証リクエスト
     * @return 検証結果
     */
    @Operation(summary = "PDF の内部署名トークンを検証する")
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<PdfSignatureVerifyResponse>> verify(
            @Valid @RequestBody PdfSignatureVerifyRequest request) {
        // 認可の真の強制点（method-security OFF でも効く）: SYSTEM_ADMIN 限定
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        PdfSignatureVerifyResponse result = pdfSignatureVerifyService.verify(request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
