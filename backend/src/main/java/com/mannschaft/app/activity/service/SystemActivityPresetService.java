package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.PresetCategory;
import com.mannschaft.app.activity.dto.CreatePresetRequest;
import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.dto.UpdatePresetRequest;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import com.mannschaft.app.activity.repository.SystemActivityTemplatePresetRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SYSTEM_ADMIN用プリセットテンプレートサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemActivityPresetService {

    private final SystemActivityTemplatePresetRepository presetRepository;
    private final ActivityMapper activityMapper;

    /**
     * プリセット一覧を取得する。
     */
    public List<PresetResponse> listPresets() {
        List<SystemActivityTemplatePresetEntity> presets =
                presetRepository.findByIsActiveTrueOrderByCategoryAscNameAsc();
        return activityMapper.toPresetResponseList(presets);
    }

    /**
     * プリセットを作成する。
     */
    @Transactional
    public PresetResponse createPreset(CreatePresetRequest request) {
        SystemActivityTemplatePresetEntity entity = SystemActivityTemplatePresetEntity.builder()
                .category(PresetCategory.valueOf(request.getCategory()))
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .isParticipantRequired(request.getIsParticipantRequired() != null
                        ? request.getIsParticipantRequired() : true)
                .defaultVisibility(request.getDefaultVisibility() != null
                        ? request.getDefaultVisibility() : "MEMBERS_ONLY")
                .fieldsJson(request.getFieldsJson())
                .build();

        SystemActivityTemplatePresetEntity saved = presetRepository.save(entity);
        log.info("プリセット作成: presetId={}, name={}", saved.getId(), saved.getName());
        return activityMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを更新する。
     */
    @Transactional
    public PresetResponse updatePreset(Long id, UpdatePresetRequest request) {
        SystemActivityTemplatePresetEntity entity = presetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.PRESET_NOT_FOUND));

        entity.update(request.getName(), request.getDescription(), request.getIcon(),
                request.getColor(),
                request.getIsParticipantRequired() != null ? request.getIsParticipantRequired() : entity.getIsParticipantRequired(),
                request.getDefaultVisibility() != null ? request.getDefaultVisibility() : entity.getDefaultVisibility(),
                request.getFieldsJson());

        SystemActivityTemplatePresetEntity saved = presetRepository.save(entity);
        log.info("プリセット更新: presetId={}", id);
        return activityMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを論理削除する。
     */
    @Transactional
    public void deletePreset(Long id) {
        SystemActivityTemplatePresetEntity entity = presetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.PRESET_NOT_FOUND));
        entity.softDelete();
        presetRepository.save(entity);
        log.info("プリセット削除: presetId={}", id);
    }
}
