package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.dto.OnboardingProgressDetailResponse;
import com.mannschaft.app.onboarding.dto.ResetProgressResponse;
import com.mannschaft.app.onboarding.dto.SkipProgressResponse;
import com.mannschaft.app.onboarding.dto.StepCompletionResponse;
import com.mannschaft.app.onboarding.service.OnboardingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * オンボーディング進捗ID指定操作コントローラー（ADMIN用）。
 */
@RestController
@RequestMapping("/api/v1/onboarding/progresses")
@RequiredArgsConstructor
public class OnboardingProgressController {

    private final OnboardingProgressService onboardingProgressService;
    private final AccessControlService accessControlService;

    /**
     * 進捗のスコープ情報から権限検証を行う。
     */
    private void verifyProgressAccess(Long progressId) {
        OnboardingProgressDetailResponse progress = onboardingProgressService.getById(progressId);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, progress.scopeId(), progress.scopeType());
    }

    /**
     * 進捗詳細を取得する。
     */
    @GetMapping("/{progressId}")
    public ApiResponse<OnboardingProgressDetailResponse> getById(@PathVariable Long progressId) {
        verifyProgressAccess(progressId);
        return ApiResponse.of(onboardingProgressService.getById(progressId));
    }

    /**
     * 進捗をスキップする。
     */
    @PostMapping("/{progressId}/skip")
    public ApiResponse<SkipProgressResponse> skip(@PathVariable Long progressId) {
        verifyProgressAccess(progressId);
        return ApiResponse.of(onboardingProgressService.skip(progressId));
    }

    /**
     * 進捗をリセットする。
     */
    @PostMapping("/{progressId}/reset")
    public ApiResponse<ResetProgressResponse> reset(@PathVariable Long progressId) {
        verifyProgressAccess(progressId);
        return ApiResponse.of(onboardingProgressService.reset(progressId));
    }

    /**
     * 管理者がステップを完了させる。
     */
    @PostMapping("/{progressId}/steps/{stepId}/complete")
    public ApiResponse<StepCompletionResponse> adminCompleteStep(
            @PathVariable Long progressId,
            @PathVariable Long stepId) {
        verifyProgressAccess(progressId);
        return ApiResponse.of(onboardingProgressService.adminCompleteStep(progressId, stepId));
    }
}
