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

        // managed entity を直接ミューテートして主キーを保持する（toBuilder().build() は id 欠落で INSERT 化するため使用しない）
        entity.applyUpdate(
                request.getName(),
                request.getDescription(),
                request.getCategory(),
                request.getFieldsJson(),
                request.getIcon(),
                request.getColor());

        SystemFormPresetEntity saved = presetRepository.save(entity);
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
