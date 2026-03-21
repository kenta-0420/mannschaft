package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.dto.CreatePresetRequest;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.UpdatePresetRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckMessagePresetEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckMessagePresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 安否確認メッセージプリセットサービス。プリセットメッセージのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyPresetService {

    private final SafetyCheckMessagePresetRepository presetRepository;
    private final SafetyCheckMapper mapper;

    /**
     * 有効なプリセット一覧を取得する。
     *
     * @return プリセット一覧
     */
    public List<SafetyPresetResponse> listActivePresets() {
        List<SafetyCheckMessagePresetEntity> entities = presetRepository.findByIsActiveTrueOrderBySortOrderAsc();
        return mapper.toPresetResponseList(entities);
    }

    /**
     * 全プリセット一覧を取得する（管理者用）。
     *
     * @return プリセット一覧
     */
    public List<SafetyPresetResponse> listAllPresets() {
        List<SafetyCheckMessagePresetEntity> entities = presetRepository.findAllByOrderBySortOrderAsc();
        return mapper.toPresetResponseList(entities);
    }

    /**
     * プリセットを作成する。
     *
     * @param req 作成リクエスト
     * @return 作成されたプリセット
     */
    @Transactional
    public SafetyPresetResponse createPreset(CreatePresetRequest req) {
        SafetyCheckMessagePresetEntity entity = SafetyCheckMessagePresetEntity.builder()
                .body(req.getBody())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();

        entity = presetRepository.save(entity);
        log.info("プリセット作成: id={}, body={}", entity.getId(), entity.getBody());
        return mapper.toPresetResponse(entity);
    }

    /**
     * プリセットを更新する。
     *
     * @param presetId プリセットID
     * @param req      更新リクエスト
     * @return 更新されたプリセット
     */
    @Transactional
    public SafetyPresetResponse updatePreset(Long presetId, UpdatePresetRequest req) {
        SafetyCheckMessagePresetEntity entity = findPresetOrThrow(presetId);

        if (req.getBody() != null) {
            entity.update(req.getBody(), req.getSortOrder());
        }
        if (req.getIsActive() != null) {
            if (req.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }

        entity = presetRepository.save(entity);
        log.info("プリセット更新: id={}", presetId);
        return mapper.toPresetResponse(entity);
    }

    /**
     * プリセットを削除する。
     *
     * @param presetId プリセットID
     */
    @Transactional
    public void deletePreset(Long presetId) {
        SafetyCheckMessagePresetEntity entity = findPresetOrThrow(presetId);
        presetRepository.delete(entity);
        log.info("プリセット削除: id={}", presetId);
    }

    // --- プライベートメソッド ---

    /**
     * プリセットを取得する。存在しない場合は例外をスローする。
     */
    private SafetyCheckMessagePresetEntity findPresetOrThrow(Long id) {
        return presetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.PRESET_NOT_FOUND));
    }
}
