package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.QueueSettingsRequest;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.repository.QueueSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 順番待ち設定サービス。スコープ単位の設定の取得・更新を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueSettingsService {

    private final QueueSettingsRepository settingsRepository;
    private final QueueMapper queueMapper;

    /**
     * 設定を取得する。存在しない場合はデフォルト値で作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 設定
     */
    public SettingsResponse getSettings(QueueScopeType scopeType, Long scopeId) {
        QueueSettingsEntity entity = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefaultSettings(scopeType, scopeId));
        return queueMapper.toSettingsResponse(entity);
    }

    /**
     * 設定を更新する。存在しない場合は作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   設定リクエスト
     * @return 更新された設定
     */
    @Transactional
    public SettingsResponse updateSettings(QueueScopeType scopeType, Long scopeId,
                                           QueueSettingsRequest request) {
        QueueSettingsEntity entity = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefaultSettings(scopeType, scopeId));

        entity.update(
                request.getNoShowTimeoutMinutes() != null
                        ? request.getNoShowTimeoutMinutes() : entity.getNoShowTimeoutMinutes(),
                request.getNoShowPenaltyEnabled() != null
                        ? request.getNoShowPenaltyEnabled() : entity.getNoShowPenaltyEnabled(),
                request.getNoShowPenaltyThreshold() != null
                        ? request.getNoShowPenaltyThreshold() : entity.getNoShowPenaltyThreshold(),
                request.getNoShowPenaltyDays() != null
                        ? request.getNoShowPenaltyDays() : entity.getNoShowPenaltyDays(),
                request.getMaxActiveTicketsPerUser() != null
                        ? request.getMaxActiveTicketsPerUser() : entity.getMaxActiveTicketsPerUser(),
                request.getAllowGuestQueue() != null
                        ? request.getAllowGuestQueue() : entity.getAllowGuestQueue(),
                request.getAlmostReadyThreshold() != null
                        ? request.getAlmostReadyThreshold() : entity.getAlmostReadyThreshold(),
                request.getHoldExtensionMinutes() != null
                        ? request.getHoldExtensionMinutes() : entity.getHoldExtensionMinutes(),
                request.getAutoAdjustServiceMinutes() != null
                        ? request.getAutoAdjustServiceMinutes() : entity.getAutoAdjustServiceMinutes(),
                request.getDisplayBoardPublic() != null
                        ? request.getDisplayBoardPublic() : entity.getDisplayBoardPublic()
        );

        QueueSettingsEntity saved = settingsRepository.save(entity);
        log.info("順番待ち設定更新: scope={}:{}", scopeType, scopeId);
        return queueMapper.toSettingsResponse(saved);
    }

    @Transactional
    private QueueSettingsEntity createDefaultSettings(QueueScopeType scopeType, Long scopeId) {
        QueueSettingsEntity entity = QueueSettingsEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
        return settingsRepository.save(entity);
    }
}
