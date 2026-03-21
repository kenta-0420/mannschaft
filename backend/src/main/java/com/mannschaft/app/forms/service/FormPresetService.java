package com.mannschaft.app.forms.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormMapper;
import com.mannschaft.app.forms.dto.CreateFormPresetRequest;
import com.mannschaft.app.forms.dto.FormPresetResponse;
import com.mannschaft.app.forms.dto.UpdateFormPresetRequest;
import com.mannschaft.app.forms.entity.SystemFormPresetEntity;
import com.mannschaft.app.forms.repository.SystemFormPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フォームプリセットサービス。システムプリセットのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormPresetService {

    private final SystemFormPresetRepository presetRepository;
    private final FormMapper formMapper;

    /**
     * 有効なプリセット一覧を取得する。
     *
     * @param category カテゴリフィルタ（null の場合は全件）
     * @return プリセットレスポンスリスト
     */
    public List<FormPresetResponse> listPresets(String category) {
        List<SystemFormPresetEntity> presets;
        if (category != null) {
            presets = presetRepository.findByCategoryAndIsActiveTrueOrderByNameAsc(category);
        } else {
            presets = presetRepository.findByIsActiveTrueOrderByNameAsc();
        }
        return formMapper.toPresetResponseList(presets);
    }

    /**
     * プリセット詳細を取得する。
     *
     * @param presetId プリセットID
     * @return プリセットレスポンス
     */
    public FormPresetResponse getPreset(Long presetId) {
        SystemFormPresetEntity entity = findPresetOrThrow(presetId);
        return formMapper.toPresetResponse(entity);
    }

    /**
     * プリセットを作成する。
     *
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたプリセットレスポンス
     */
    @Transactional
    public FormPresetResponse createPreset(Long userId, CreateFormPresetRequest request) {
        SystemFormPresetEntity entity = SystemFormPresetEntity.builder()
                .name(request.getName())
                .description(request.getDescription())
                .category(request.getCategory())
                .fieldsJson(request.getFieldsJson())
                .icon(request.getIcon())
                .color(request.getColor())
                .createdBy(userId)
                .build();

        SystemFormPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット作成: presetId={}", saved.getId());
        return formMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを更新する。
     *
     * @param presetId プリセットID
     * @param request  更新リクエスト
     * @return 更新されたプリセットレスポンス
     */
    @Transactional
    public FormPresetResponse updatePreset(Long presetId, UpdateFormPresetRequest request) {
        SystemFormPresetEntity entity = findPresetOrThrow(presetId);

        SystemFormPresetEntity updated = entity.toBuilder()
                .name(request.getName() != null ? request.getName() : entity.getName())
                .description(request.getDescription() != null ? request.getDescription() : entity.getDescription())
                .category(request.getCategory() != null ? request.getCategory() : entity.getCategory())
                .fieldsJson(request.getFieldsJson() != null ? request.getFieldsJson() : entity.getFieldsJson())
                .icon(request.getIcon() != null ? request.getIcon() : entity.getIcon())
                .color(request.getColor() != null ? request.getColor() : entity.getColor())
                .build();

        SystemFormPresetEntity saved = presetRepository.save(updated);
        log.info("プリセット更新: presetId={}", presetId);
        return formMapper.toPresetResponse(saved);
    }

    /**
     * プリセットを論理削除する。
     *
     * @param presetId プリセットID
     */
    @Transactional
    public void deletePreset(Long presetId) {
        SystemFormPresetEntity entity = findPresetOrThrow(presetId);
        entity.softDelete();
        presetRepository.save(entity);
        log.info("プリセット削除: presetId={}", presetId);
    }

    /**
     * プリセットを取得する。存在しない場合は例外をスローする。
     */
    private SystemFormPresetEntity findPresetOrThrow(Long presetId) {
        return presetRepository.findById(presetId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.PRESET_NOT_FOUND));
    }
}
