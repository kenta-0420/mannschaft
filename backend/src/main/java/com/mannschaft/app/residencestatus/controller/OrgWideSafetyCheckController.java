package com.mannschaft.app.residencestatus.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.residencestatus.dto.OrgWideSafetyCheckDto;
import com.mannschaft.app.residencestatus.service.OrgWideSafetyCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F09.16 S3-C 管理組合横展開安否確認コントローラー。
 *
 * <p>認可は Service 層に委譲する。Controller はパスパラメータの組織 ID と
 * 現在ユーザー ID の引き渡しのみを行う。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/residence-status/org-wide-safety-checks")
@Tag(name = "横展開安否確認（F09.16）", description = "F09.16 居住実態管理 - 管理組合横展開安否確認 API")
@RequiredArgsConstructor
public class OrgWideSafetyCheckController {

    private final OrgWideSafetyCheckService safetyCheckService;

    /**
     * 管理組合横展開安否確認を発動する（ADMIN のみ）。
     */
    @PostMapping
    @Operation(summary = "横展開安否確認発動（ADMIN のみ）")
    public ResponseEntity<ApiResponse<OrgWideSafetyCheckDto>> triggerSafetyCheck(
            @PathVariable Long orgId,
            @Valid @RequestBody OrgWideSafetyCheckCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        OrgWideSafetyCheckDto dto = safetyCheckService.triggerOrgWideSafetyCheck(
                orgId, userId, request.triggerReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 組織の未クローズな横展開安否確認一覧を取得する（ADMIN のみ）。
     */
    @GetMapping("/active")
    @Operation(summary = "未クローズの横展開安否確認一覧（ADMIN のみ）")
    public ResponseEntity<ApiResponse<List<OrgWideSafetyCheckDto>>> getActiveChecks(
            @PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<OrgWideSafetyCheckDto> list = safetyCheckService.getActiveChecks(orgId, userId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    // ─────────────────────────────────────────────
    // リクエスト DTO（record）
    // ─────────────────────────────────────────────

    /**
     * 横展開安否確認発動リクエスト。
     */
    public record OrgWideSafetyCheckCreateRequest(
            @NotNull Long organizationId,
            @NotBlank String triggerReason
    ) {}
}
