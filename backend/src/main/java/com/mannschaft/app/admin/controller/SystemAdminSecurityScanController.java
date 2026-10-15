package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.SecurityScanStatusResponse;
import com.mannschaft.app.admin.service.SecurityScanStatusService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * セキュリティスキャン状態コントローラー（システム管理者向け）。
 *
 * <p>GitHub Actions の OWASP Dependency-Check 週次スキャン（security-scan.yml）の
 * 最新実行状態をシステム管理画面に提供する。
 * SecurityConfig の {@code /api/v1/system-admin/**} SYSTEM_ADMIN 制限に加え、
 * メソッドレベルでも {@code @PreAuthorize} を付与して二重防御する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/security-scan")
@Tag(name = "システム管理 - セキュリティスキャン", description = "OWASP Dependency-Check スキャン状態 API")
@RequiredArgsConstructor
public class SystemAdminSecurityScanController {

    private final SecurityScanStatusService securityScanStatusService;

    /**
     * OWASP Dependency-Check スキャンの最新実行状態を返す。
     *
     * @return スキャン状態（conclusion / runUrl / runAt）
     */
    @GetMapping("/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(
            summary = "セキュリティスキャン状態取得",
            description = "GitHub Actions の OWASP Dependency-Check 週次スキャンの最新実行状態を返す。"
                    + " GitHub API 失敗時は conclusion=UNKNOWN を返す（例外は握りつぶさずログに記録する）。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SecurityScanStatusResponse>> getSecurityScanStatus() {
        SecurityScanStatusResponse status = securityScanStatusService.getStatus();
        return ResponseEntity.ok(ApiResponse.of(status));
    }
}
