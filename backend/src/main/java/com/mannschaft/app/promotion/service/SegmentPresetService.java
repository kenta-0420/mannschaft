package com.mannschaft.app.promotion.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;

    /**
     * プリセット一覧を取得する。
     * 認可根治戦役 Wave2-2B: スコープメンバーであることを要求する（非メンバーは403）。
     */
    public List<SegmentPresetResponse> list(String scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType);
        List<SavedSegmentPresetEntity> entities = presetRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
        return promotionMapper.toSegmentPresetResponseList(entities);
    }

    /**
     * プリセットを作成する。
     * 認可根治戦役 Wave2-2B: 作成先スコープのADMIN/DEPUTY_ADMINであることを要求する。
     */
    @Transactional
    public SegmentPresetResponse create(String scopeType, Long scopeId, Long userId, CreateSegmentPresetRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
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
     * 認可根治戦役 Wave2-2B: entity由来のscopeでADMIN/DEPUTY_ADMINを検証する（BOLA対策）。
     * 他組織の配信セグメントプリセット改ざんを封鎖する。
     */
    @Transactional
    public SegmentPresetResponse update(String scopeType, Long scopeId, Long id, CreateSegmentPresetRequest request,
                                         Long actorUserId) {
        SavedSegmentPresetEntity entity = presetRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.PRESET_NOT_FOUND));
        accessControlService.checkAdminOrAbove(actorUserId, entity.getScopeId(), entity.getScopeType());
        entity.update(request.getName(), request.getConditions());
        SavedSegmentPresetEntity saved = presetRepository.save(entity);
        log.info("プリセット更新: id={}", id);
        return promotionMapper.toSegmentPresetResponse(saved);
    }

    /**
     * プリセットを削除する。
     * 認可根治戦役 Wave2-2B: entity由来のscopeでADMIN/DEPUTY_ADMINを検証する（BOLA対策）。
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id, Long actorUserId) {
        SavedSegmentPresetEntity entity = presetRepository.findByIdAndScope(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(PromotionErrorCode.PRESET_NOT_FOUND));
        accessControlService.checkAdminOrAbove(actorUserId, entity.getScopeId(), entity.getScopeType());
        entity.softDelete();
        presetRepository.save(entity);
        log.info("プリセット削除: id={}", id);
    }
}
