package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 * メンバー自身のオンボーデ��ング進捗操作コントローラー。
 * <p>
 * ログイン中のメンバーが自分のオン��ーディング進捗を確認し、ステップを完了させるためのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/onboarding/progresses/me")
@RequiredArgsConstructor
public class OnboardingMeController {

    private final OnboardingProgressService onboardingProgressService;

    /**
     * 自分のオンボーディング進捗一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<OnboardingProgressResponse>> list(
            @RequestParam(required = false) OnboardingProgressStatus status) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(onboardingProgressService.listByUser(userId, status));
    }

    /**
     * 自分の��ンボーディング進捗詳細���取得する。
     */
    @GetMapping("/{progressId}")
    public ApiResponse<OnboardingProgressDetailResponse> getById(@PathVariable Long progressId) {
        return ApiResponse.of(onboardingProgressService.getById(progressId));
    }

    /**
     * メンバー自身がステップ��完了させる。
     */
    @PostMapping("/{progressId}/steps/{stepId}/complete")
    public ApiResponse<StepCompletionResponse> completeStep(
            @PathVariable Long progressId,
            @PathVariable Long stepId) {
        return ApiResponse.of(onboardingProgressService.completeStepByMember(progressId, stepId));
    }
}
