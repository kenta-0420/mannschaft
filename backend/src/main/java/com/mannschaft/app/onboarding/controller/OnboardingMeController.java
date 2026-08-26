package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.dto.OnboardingProgressDetailResponse;
import com.mannschaft.app.onboarding.dto.OnboardingProgressResponse;
import com.mannschaft.app.onboarding.dto.StepCompletionResponse;
import com.mannschaft.app.onboarding.service.OnboardingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * メンバー自身のオンボーディング進捗操作コントローラー。
 * <p>
 * ログイン中のメンバーが自分のオンボーディング進捗を確認し、ステップを完了させるためのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/onboarding/progresses/me")
@RequiredArgsConstructor
public class OnboardingMeController {

    private final OnboardingProgressService onboardingProgressService;

    /**
     * 自分のオンボーディング進捗一覧を取得する。
     */
    @SelfScopedEndpoint("OnboardingProgressService#listByUser が "
            + "SecurityUtils.getCurrentUserId() のみを検索条件に束縛する")
    @GetMapping
    public ApiResponse<List<OnboardingProgressResponse>> list(
            @RequestParam(required = false) OnboardingProgressStatus status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(onboardingProgressService.listByUser(userId, status));
    }

    /**
     * 自分のオンボーディング進捗詳細を取得する。
     *
     * <p>認可根治戦役 Wave7: 本人所有チェックを {@code OnboardingProgressService#getByIdForMember}
     * に敷いた（他人の progressId 指定は 404 で存在秘匿）。</p>
     */
    @AuthorizedInService
    @GetMapping("/{progressId}")
    public ApiResponse<OnboardingProgressDetailResponse> getById(@PathVariable Long progressId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(onboardingProgressService.getByIdForMember(progressId, userId));
    }

    /**
     * メンバー自身がステップを完了させる。
     *
     * <p>認可根治戦役 Wave7: 本人所有チェックを {@code OnboardingProgressService#completeStepByMember}
     * に敷いた（他人の progressId 指定は 404 で存在秘匿）。</p>
     */
    @AuthorizedInService
    @PostMapping("/{progressId}/steps/{stepId}/complete")
    public ApiResponse<StepCompletionResponse> completeStep(
            @PathVariable Long progressId,
            @PathVariable Long stepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(onboardingProgressService.completeStepByMember(progressId, stepId, userId));
    }
}
