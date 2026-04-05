package com.mannschaft.app.promotion.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.PromotionErrorCode;
import com.mannschaft.app.promotion.dto.CreateSegmentPresetRequest;
import com.mannschaft.app.promotion.dto.SegmentPresetResponse;
import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.SavedSegmentPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * セグメントプリセット管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SegmentPresetService {

    private final SavedSegmentPresetRepository presetRepository;
    private final PromotionMapper promotionMapper;

    /**
     * プリセット一覧を取得する。
     */
    public List<SegmentPresetResponse> list(String scopeType, Long scopeId) {
        List<SavedSegmentPresetEntity> entities = presetRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
        return promotionMapper.toSegmentPresetResponseList(entities);
    }

    /**
     * プリセットを作成する。
     */
    @Transactional
    public SegmentPresetResponse create(String scopeType, Long scopeId, Long userId, CreateSegmentPresetRequest request) {
        SavedSegmentPresetEntity entity = SavedSegmentPresetEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .conditions(request.getConditions())
                .createdBy(userId)
                .build();
        SavedSegmentPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット作成: scopeType={}, scopeId={}, id={}", scopeType, scopeId, saved.getId());
        return promotionMapper.toSegmentPresetResponse(saved);
    }

    /**
     * プリセットを更新する。
     */
    @Transactional
    public SegmentPresetResponse update(String scopeType, Long scopeId, Long id, CreateSegmentPresetRequest request) {
        SavedSegmentPresetEntity entity = presetRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.PRESET_NOT_FOUND));
        entity.update(request.getName(), request.getConditions());
        SavedSegmentPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット更新: id={}", id);
        return promotionMapper.toSegmentPresetResponse(saved);
    }

    /**
     * プリセットを削除する。
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id) {
        SavedSegmentPresetEntity entity = presetRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.PRESET_NOT_FOUND));
        entity.softDelete();
        presetRepository.save(entity);
        log.info("プリセット削除: id={}", id);
    }
}
