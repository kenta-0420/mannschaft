package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.dto.CreateOnboardingTemplateRequest;
import com.mannschaft.app.onboarding.dto.OnboardingTemplateResponse;
import com.mannschaft.app.onboarding.service.OnboardingTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チームスコープのオンボーディングテンプレート管理コントローラ��。
 * <p>
 * チーム管理者がチーム固有のオンボーディングテンプレートを作成・一覧取得するためのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/onboarding/templates")
@RequiredArgsConstructor
public class TeamOnboardingTemplateController {

    private final OnboardingTemplateService onboardingTemplateService;
    private final AccessControlService accessControlService;

    /**
     * チームスコープのテンプレート一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<OnboardingTemplateResponse>> list(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(onboardingTemplateService.listByScope("TEAM", teamId));
    }

    /**
     * チームスコープのテンプレートを新規作成する。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OnboardingTemplateResponse> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateOnboardingTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        return ApiResponse.of(onboardingTemplateService.create("TEAM", teamId, userId, request));
    }
}
