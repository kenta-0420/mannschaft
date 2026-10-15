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
 * チームスコープのオンボーディング進捗管理コントローラー（ADMIN用）。
 * <p>
 * チーム管理者がメンバーのオンボーディング進捗を一覧取得し、リマインドを送信するためのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/onboarding")
@RequiredArgsConstructor
public class TeamOnboardingProgressController {

    private final OnboardingProgressService onboardingProgressService;
    private final AccessControlService accessControlService;

    /**
     * チームスコープの進捗一覧を取得する。
     */
    @GetMapping("/progresses")
    public PagedResponse<OnboardingProgressResponse> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) OnboardingProgressStatus status,
            Pageable pageable) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        Page<OnboardingProgressResponse> page =
                onboardingProgressService.listByScope("TEAM", teamId, status, pageable);
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
     * チームスコープのメンバーにリマインドを送信��る。
     */
    @PostMapping("/remind")
    public ApiResponse<RemindResponse> sendReminders(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(onboardingProgressService.sendReminders("TEAM", teamId));
    }
}
