package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.dto.OnboardingProgressResponse;
import com.mannschaft.app.onboarding.dto.RemindResponse;
import com.mannschaft.app.onboarding.service.OnboardingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織スコープのオンボーディング進捗管理コントローラー（ADMIN用）。
 * <p>
 * 組織管理者がメンバーのオンボーディング進捗を一覧取得し、リマインドを送信するためのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/onboarding")
@RequiredArgsConstructor
public class OrgOnboardingProgressController {

    private final OnboardingProgressService onboardingProgressService;
    private final AccessControlService accessControlService;

    /**
     * 組織スコープの進捗一覧を取得する。
     */
    @GetMapping("/progresses")
    public PagedResponse<OnboardingProgressResponse> list(
            @PathVariable Long orgId,
            @RequestParam(required = false) OnboardingProgressStatus status,
            Pageable pageable) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        Page<OnboardingProgressResponse> page =
                onboardingProgressService.listByScope("ORGANIZATION", orgId, status, pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    /**
     * 組織スコープのメンバーにリマインドを送信する。
     */
    @PostMapping("/remind")
    public ApiResponse<RemindResponse> sendReminders(@PathVariable Long orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");
        return ApiResponse.of(onboardingProgressService.sendReminders("ORGANIZATION", orgId));
    }
}
