package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.onboarding.OnboardingErrorCode;
import com.mannschaft.app.onboarding.OnboardingMapper;
import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import com.mannschaft.app.onboarding.dto.*;
import com.mannschaft.app.onboarding.entity.SystemOnboardingPresetEntity;
import com.mannschaft.app.onboarding.repository.SystemOnboardingPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * システムオンボーディングプリセット管理サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OnboardingPresetService {

    private final SystemOnboardingPresetRepository presetRepository;
    private final OnboardingMapper mapper;

    /**
     * プリセットを作成する（SYSTEM_ADMIN用）。
     */
    @Transactional
    public PresetResponse create(CreatePresetRequest request, Long userId) {
        SystemOnboardingPresetEntity entity = SystemOnboardingPresetEntity.builder()
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .welcomeMessage(request.welcomeMessage())
                .isOrderEnforced(request.isOrderEnforced() != null ? request.isOrderEnforced() : false)
                .deadlineDays(request.deadlineDays() != null ? request.deadlineDays().shortValue() : null)
                .stepsJson(request.stepsJson())
                .isActive(true)
                .sortOrder(0)
                .createdBy(userId)
                .build();

        SystemOnboardingPresetEntity saved = presetRepository.save(entity);
        log.info("オンボーディングプリセット作成: id={}, name={}", saved.getId(), saved.getName());
        return mapper.toPresetResponse(saved);
    }

    /**
     * プリセットを更新する。
     */
    @Transactional
    public PresetResponse update(Long presetId, UpdatePresetRequest request) {
        SystemOnboardingPresetEntity entity = findPresetOrThrow(presetId);

        SystemOnboardingPresetEntity updated = entity.toBuilder()
                .name(request.name() != null ? request.name() : entity.getName())
                .description(request.description() != null ? request.description() : entity.getDescription())
                .category(request.category() != null ? request.category() : entity.getCategory())
                .welcomeMessage(request.welcomeMessage() != null ? request.welcomeMessage() : entity.getWelcomeMessage())
                .isOrderEnforced(request.isOrderEnforced() != null ? request.isOrderEnforced() : entity.getIsOrderEnforced())
                .deadlineDays(request.deadlineDays() != null ? request.deadlineDays().shortValue() : entity.getDeadlineDays())
                .stepsJson(request.stepsJson() != null ? request.stepsJson() : entity.getStepsJson())
                .isActive(request.isActive() != null ? request.isActive() : entity.getIsActive())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : entity.getSortOrder())
                .build();

        SystemOnboardingPresetEntity saved = presetRepository.save(updated);
        log.info("オンボーディングプリセット更新: id={}", presetId);
        return mapper.toPresetResponse(saved);
    }

    /**
     * プリセットを論理削除する。
     */
    @Transactional
    public void delete(Long presetId) {
        SystemOnboardingPresetEntity entity = findPresetOrThrow(presetId);
        entity.softDelete();
        presetRepository.save(entity);
        log.info("オンボーディングプリセット削除: id={}", presetId);
    }

    /**
     * プリセット詳細を取得する。
     */
    public PresetResponse getById(Long presetId) {
        return mapper.toPresetResponse(findPresetOrThrow(presetId));
    }

    /**
     * SYSTEM_ADMIN用全件一覧を取得する。
     */
    public List<PresetResponse> listAll() {
        return mapper.toPresetResponseList(presetRepository.findAll());
    }

    /**
     * ADMIN用カタログ一覧を取得する（isActive=trueのみ）。
     */
    public List<PresetCatalogResponse> listCatalog() {
        return mapper.toPresetCatalogResponseList(
                presetRepository.findByIsActiveTrueAndDeletedAtIsNullOrderBySortOrder());
    }

    /**
     * カテゴリ別カタログ一覧を取得する。
     */
    public List<PresetCatalogResponse> listCatalogByCategory(OnboardingPresetCategory category) {
        return mapper.toPresetCatalogResponseList(
                presetRepository.findByCategoryAndIsActiveTrueAndDeletedAtIsNull(category));
    }

    private SystemOnboardingPresetEntity findPresetOrThrow(Long presetId) {
        return presetRepository.findById(presetId)
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_012));
    }
}
