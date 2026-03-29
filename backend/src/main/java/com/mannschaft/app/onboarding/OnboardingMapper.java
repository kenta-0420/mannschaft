package com.mannschaft.app.onboarding;

import com.mannschaft.app.onboarding.dto.*;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateStepEntity;
import com.mannschaft.app.onboarding.entity.SystemOnboardingPresetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * オンボーディング機能のMapStructマッパー。
 */
@Mapper(componentModel = "spring")
public interface OnboardingMapper {

    // ========================================
    // テンプレート関連
    // ========================================

    @Mapping(target = "steps", source = "steps")
    OnboardingTemplateResponse toTemplateResponse(OnboardingTemplateEntity entity, List<StepResponse> steps);

    StepResponse toStepResponse(OnboardingTemplateStepEntity entity);

    List<StepResponse> toStepResponseList(List<OnboardingTemplateStepEntity> entities);

    @Mapping(target = "templateId", ignore = true)
    OnboardingTemplateStepEntity toStepEntity(CreateStepRequest request);

    List<OnboardingTemplateStepEntity> toStepEntityList(List<CreateStepRequest> requests);

    // ========================================
    // プリセット関連
    // ========================================

    PresetResponse toPresetResponse(SystemOnboardingPresetEntity entity);

    List<PresetResponse> toPresetResponseList(List<SystemOnboardingPresetEntity> entities);

    PresetCatalogResponse toPresetCatalogResponse(SystemOnboardingPresetEntity entity);

    List<PresetCatalogResponse> toPresetCatalogResponseList(List<SystemOnboardingPresetEntity> entities);
}
