package com.mannschaft.app.securityincident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.securityincident.dto.SecurityIncidentCreateRequest;
import com.mannschaft.app.securityincident.dto.SecurityIncidentResponse;
import com.mannschaft.app.securityincident.dto.SecurityIncidentUpdateRequest;
import com.mannschaft.app.securityincident.service.SecurityIncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * セキュリティインシデント管理コントローラー（システム管理者向け）。
 *
 * <p>GDPR Article 33 の 72 時間 DPA 通知義務を管理するための API を提供する。
 * SecurityConfig の {@code /api/v1/system-admin/**} SYSTEM_ADMIN 制限に加え、
 * メソッドレベルでも {@code @PreAuthorize} を付与して二重防御する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/security-incidents")
@Tag(name = "システム管理 - セキュリティインシデント", description = "GDPR Article 33 対応セキュリティインシデント管理 API")
@RequiredArgsConstructor
public class SystemAdminSecurityIncidentController {

    private final SecurityIncidentService service;

    /**
     * セキュリティインシデントを登録する。
     *
     * @param req 登録リクエスト
     * @return 登録したインシデント
     */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "セキュリティインシデント登録", description = "新規セキュリティインシデントを登録する。")
    public ApiResponse<SecurityIncidentResponse> create(
            @RequestBody @Valid SecurityIncidentCreateRequest req) {
        return ApiResponse.of(service.create(req));
    }

    /**
     * セキュリティインシデント一覧を返す（OPEN 優先・検出時刻降順）。
     *
     * @return インシデント一覧
     */
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "セキュリティインシデント一覧", description = "OPEN 優先・検出時刻降順で一覧を返す。")
    public ApiResponse<List<SecurityIncidentResponse>> findAll() {
        return ApiResponse.of(service.findAll());
    }

    /**
     * セキュリティインシデントのステータス更新・DPA 通知を記録する。
     *
     * @param id  インシデント ID
     * @param req 更新リクエスト
     * @return 更新後のインシデント
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "セキュリティインシデント更新", description = "ステータス変更・DPA 通知記録を行う。")
    public ApiResponse<SecurityIncidentResponse> update(
            @PathVariable UUID id,
            @RequestBody @Valid SecurityIncidentUpdateRequest req) {
        return ApiResponse.of(service.update(id, req));
    }
}
