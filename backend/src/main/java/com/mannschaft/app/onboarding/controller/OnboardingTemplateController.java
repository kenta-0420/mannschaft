package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.dto.ActivateTemplateResponse;
import com.mannschaft.app.onboarding.dto.OnboardingTemplateResponse;
import com.mannschaft.app.onboarding.dto.UpdateOnboardingTemplateRequest;
import com.mannschaft.app.onboarding.service.OnboardingTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * オンボーディングテンプレートID指定操作コントローラー（スコープ横断）。
 */
@RestController
@RequestMapping("/api/v1/onboarding/templates")
@RequiredArgsConstructor
public class OnboardingTemplateController {

    private final OnboardingTemplateService onboardingTemplateService;
    private final AccessControlService accessControlService;

    /**
     * テンプレートのスコープ情報から権限検証を行う。
     */
    private void verifyTemplateAccess(Long templateId) {
        OnboardingTemplateResponse template = onboardingTemplateService.getById(templateId);
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, template.scopeId(), template.scopeType());
    }

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/{templateId}")
    public ApiResponse<OnboardingTemplateResponse> getById(@PathVariable Long templateId) {
        verifyTemplateAccess(templateId);
        return ApiResponse.of(onboardingTemplateService.getById(templateId));
    }

    /**
     * テンプレートを更新する（DRAFTステータスのみ更新可能）。
     */
    @PutMapping("/{templateId}")
    public ApiResponse<OnboardingTemplateResponse> update(
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateOnboardingTemplateRequest request) {
        verifyTemplateAccess(templateId);
        return ApiResponse.of(onboardingTemplateService.update(templateId, request));
    }

    /**
     * テンプレートを有効化する。
     */
    @PostMapping("/{templateId}/activate")
    public ApiResponse<ActivateTemplateResponse> activate(@PathVariable Long templateId) {
        verifyTemplateAccess(templateId);
        return ApiResponse.of(onboardingTemplateService.activate(templateId));
    }

    /**
     * テンプレートをアーカイブする。
     */
    @PostMapping("/{templateId}/archive")
    public ApiResponse<OnboardingTemplateResponse> archive(@PathVariable Long templateId) {
        verifyTemplateAccess(templateId);
        return ApiResponse.of(onboardingTemplateService.archive(templateId));
    }

    /**
     * テンプレートを削除する。
     */
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long templateId) {
        verifyTemplateAccess(templateId);
        onboardingTemplateService.delete(templateId);
    }

    /**
     * テンプレートを複製する。
     */
    @PostMapping("/{templateId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OnboardingTemplateResponse> duplicate(@PathVariable Long templateId) {
        verifyTemplateAccess(templateId);
        return ApiResponse.of(onboardingTemplateService.duplicate(templateId));
    }
}
