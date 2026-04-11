package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.CancellationPolicyResponse;
import com.mannschaft.app.recruitment.dto.UpdateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.11 募集型予約: キャンセルポリシー個別操作 Controller (§9.9, Phase 5a)。
 */
@RestController
@RequestMapping("/api/v1/cancellation-policies")
@Tag(name = "F03.11 募集型予約 / キャンセルポリシー", description = "キャンセルポリシー詳細・編集・削除")
@RequiredArgsConstructor
public class CancellationPolicyController {

    private final RecruitmentCancellationPolicyService policyService;

    @GetMapping("/{id}")
    @Operation(summary = "ポリシー詳細 (段階含む)")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                policyService.getPolicy(id, SecurityUtils.getCurrentUserId())));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "ポリシー編集 (is_template_policy=true のみ)")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCancellationPolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                policyService.updatePolicy(id, SecurityUtils.getCurrentUserId(), request)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "ポリシー論理削除")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        policyService.archivePolicy(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
