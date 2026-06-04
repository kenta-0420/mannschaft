package com.mannschaft.app.payment.admin;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.admin.dto.FeePolicyAssignmentCreateRequest;
import com.mannschaft.app.payment.admin.dto.FeePolicyAssignmentResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン割当（{@code fee_policy_assignments}）のシステム管理者 CRUD（設計書 02 §11）。
 *
 * <p>{@code source_kind}（＋任意 {@code sub_key}）→ {@code policy_key} の割当を管理する。割当変更は
 * <b>新規課金にのみ反映</b>され、既存 escrow は焼き付け済み率で不変（遡及防止・R1）。認可は SYSTEM_ADMIN。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/fee-policy-assignments")
@Tag(name = "システム管理 - 手数料パターン割当管理", description = "F22.1 統一決済 手数料パターン割当 CRUD（SYSTEM_ADMIN専用）")
@RequiredArgsConstructor
public class SystemAdminFeePolicyAssignmentController {

    private final FeePolicyAdminService service;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン割当一覧", description = "未削除の割当一覧（source_kind＋sub_key → policy_key）を作成順で返す。")
    public ResponseEntity<ApiResponse<List<FeePolicyAssignmentResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.of(service.listAssignments()));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン割当作成",
            description = "割当を作成する。参照先 policy 不在は 404・無効は 422。同条件の既存割当は 409。"
                    + "割当変更は新規課金のみ反映（既存取引は焼き付け済みで不変）。")
    public ResponseEntity<ApiResponse<FeePolicyAssignmentResponse>> create(
            @Valid @RequestBody FeePolicyAssignmentCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.createAssignment(request, actorUserId)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手数料パターン割当解除", description = "割当を解除する（論理削除）。既存課金には影響しない（焼き付け済みで不変）。")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        service.deleteAssignment(id, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
