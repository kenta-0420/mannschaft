package com.mannschaft.app.payment.admin;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.admin.dto.FeePolicyResponse;
import com.mannschaft.app.payment.admin.dto.FeePolicyUpsertRequest;
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
 * F22.1 市（Market）統一決済 R2: 手数料パターン（{@code fee_policies}）のシステム管理者 CRUD（設計書 02 §11）。
 *
 * <p>既存 {@code SystemAdminNavFeaturesController} と同型（{@code /api/v1/system-admin/...} ＋
 * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} ＋ {@code {policyKey}} 自然キーパス）。
 * 認可は SecurityConfig の {@code /api/v1/system-admin/**} ルール（hasRole SYSTEM_ADMIN）と
 * メソッドガードの二重で担保する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/fee-policies")
@Tag(name = "システム管理 - 手数料パターン管理", description = "F22.1 統一決済 手数料パターン CRUD（SYSTEM_ADMIN専用）")
@RequiredArgsConstructor
public class SystemAdminFeePolicyController {

    private final FeePolicyAdminService service;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン一覧", description = "全パターンを返す（enabled=false 含む全件・policy_key 昇順・割当数付き）。")
    public ResponseEntity<ApiResponse<List<FeePolicyResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.of(service.listPolicies()));
    }

    @GetMapping("/{policyKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン詳細", description = "指定 policy_key のパターン詳細を返す。不在は 404。")
    public ResponseEntity<ApiResponse<FeePolicyResponse>> get(@PathVariable String policyKey) {
        return ResponseEntity.ok(ApiResponse.of(service.getPolicy(policyKey)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン新規", description = "新しいパターンを作成する。既存キーは 409（更新は PUT）。率・固定額の業務制約あり。")
    public ResponseEntity<ApiResponse<FeePolicyResponse>> create(
            @Valid @RequestBody FeePolicyUpsertRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.createPolicy(request, actorUserId)));
    }

    @PutMapping("/{policyKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン更新", description = "率・固定額・enabled・説明を更新する。改定は新規徴収のみ反映（遡及しない）。DEFAULT の無効化は不可。")
    public ResponseEntity<ApiResponse<FeePolicyResponse>> update(
            @PathVariable String policyKey,
            @Valid @RequestBody FeePolicyUpsertRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(service.updatePolicy(policyKey, request, actorUserId)));
    }

    @DeleteMapping("/{policyKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン無効化", description = "パターンを無効化する（enabled=false）。DEFAULT は不可（409）。")
    public ResponseEntity<Void> disable(@PathVariable String policyKey) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        service.disablePolicy(policyKey, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
