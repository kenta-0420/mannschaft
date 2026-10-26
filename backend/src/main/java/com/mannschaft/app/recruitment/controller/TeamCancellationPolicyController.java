package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancellationPolicyResponse;
import com.mannschaft.app.recruitment.dto.CreateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * F03.11 募集型予約: チームのキャンセルポリシー Controller (§9.9, Phase 5a)。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/cancellation-policies")
@Tag(name = "F03.11 募集型予約 / キャンセルポリシー (チーム)", description = "チームのキャンセルポリシー作成・一覧")
@RequiredArgsConstructor
public class TeamCancellationPolicyController {

    private final RecruitmentCancellationPolicyService policyService;

    @GetMapping
    @Operation(summary = "チームのキャンセルポリシー一覧")
    public ResponseEntity<ApiResponse<List<CancellationPolicyResponse>>> list(@PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(
                policyService.listByScope(RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping
    @Operation(summary = "キャンセルポリシー作成 (テンプレート用 or 募集用)")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCancellationPolicyRequest request) {
        CancellationPolicyResponse response = policyService.createPolicy(
                RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
