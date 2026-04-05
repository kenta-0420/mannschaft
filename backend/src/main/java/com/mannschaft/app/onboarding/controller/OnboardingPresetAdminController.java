package com.mannschaft.app.onboarding.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.onboarding.dto.CreatePresetRequest;
import com.mannschaft.app.onboarding.dto.PresetResponse;
import com.mannschaft.app.onboarding.dto.UpdatePresetRequest;
import com.mannschaft.app.onboarding.service.OnboardingPresetService;
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

import java.util.List;

/**
 * SYSTEM_ADMIN向けオンボーディングプリセ���ト管理コントロー���ー。
 * <p>
 * システム管理者がプリセットテンプレートのCRUD���作を行うためのエン���ポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/onboarding/presets")
@RequiredArgsConstructor
public class OnboardingPresetAdminController {

    private final OnboardingPresetService onboardingPresetService;

    /**
     * プリセット一覧を取得する。
     */
    @GetMapping
    public ApiResponse<List<PresetResponse>> listAll() {
        return ApiResponse.of(onboardingPresetService.listAll());
    }

    /**
     * プリセットを新規作成する。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PresetResponse> create(
            @Valid @RequestBody CreatePresetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(onboardingPresetService.create(request, userId));
    }

    /**
     * プリセットを取得する。
     */
    @GetMapping("/{presetId}")
    public ApiResponse<PresetResponse> getById(@PathVariable Long presetId) {
        return ApiResponse.of(onboardingPresetService.getById(presetId));
    }

    /**
     * プリセットを更新する。
     */
    @PutMapping("/{presetId}")
    public ApiResponse<PresetResponse> update(
            @PathVariable Long presetId,
            @Valid @RequestBody UpdatePresetRequest request) {
        return ApiResponse.of(onboardingPresetService.update(presetId, request));
    }

    /**
     * プリセットを論理削��する。
     */
    @DeleteMapping("/{presetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long presetId) {
        onboardingPresetService.delete(presetId);
    }
}
