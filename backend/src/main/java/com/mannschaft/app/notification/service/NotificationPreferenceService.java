package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知設定サービス。スコープ別・種別別の通知設定を管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationTypePreferenceRepository typePreferenceRepository;
    private final NotificationMapper notificationMapper;

    /**
     * ユーザーの通知設定一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 通知設定レスポンスリスト
     */
    public List<PreferenceResponse> listPreferences(Long userId) {
        List<NotificationPreferenceEntity> entities = preferenceRepository.findByUserId(userId);
        return notificationMapper.toPreferenceResponseList(entities);
    }

    /**
     * 通知設定を更新する（存在しない場合は新規作成）。
     *
     * @param userId  ユーザーID
     * @param request 更新リクエスト
     * @return 更新された通知設定レスポンス
     */
    @Transactional
    public PreferenceResponse updatePreference(Long userId, PreferenceUpdateRequest request) {
        NotificationPreferenceEntity entity = preferenceRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, request.getScopeType(), request.getScopeId())
                .orElse(null);

        if (entity == null) {
            entity = NotificationPreferenceEntity.builder()
                    .userId(userId)
                    .scopeType(request.getScopeType())
                    .scopeId(request.getScopeId())
                    .isEnabled(request.getIsEnabled())
                    .build();
        } else {
            entity.updateEnabled(request.getIsEnabled());
        }

        NotificationPreferenceEntity saved = preferenceRepository.save(entity);
        log.info("通知設定更新: userId={}, scopeType={}, scopeId={}, enabled={}",
                userId, request.getScopeType(), request.getScopeId(), request.getIsEnabled());
        return notificationMapper.toPreferenceResponse(saved);
    }

    /**
     * ユーザーの通知種別設定一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 通知種別設定レスポンスリスト
     */
    public List<TypePreferenceResponse> listTypePreferences(Long userId) {
        List<NotificationTypePreferenceEntity> entities = typePreferenceRepository.findByUserId(userId);
        return notificationMapper.toTypePreferenceResponseList(entities);
    }

    /**
     * 通知種別設定を一括更新する（存在しない場合は新規作成）。
     *
     * @param userId  ユーザーID
     * @param request 一括更新リクエスト
     * @return 更新された通知種別設定レスポンスリスト
     */
    @Transactional
    public List<TypePreferenceResponse> bulkUpdateTypePreferences(Long userId,
                                                                   TypePreferenceBulkUpdateRequest request) {
        List<NotificationTypePreferenceEntity> results = new ArrayList<>();

        for (TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry : request.getPreferences()) {
            NotificationTypePreferenceEntity entity = typePreferenceRepository
                    .findByUserIdAndNotificationType(userId, entry.getNotificationType())
                    .orElse(null);

            if (entity == null) {
                entity = NotificationTypePreferenceEntity.builder()
                        .userId(userId)
                        .notificationType(entry.getNotificationType())
                        .isEnabled(entry.getIsEnabled())
                        .build();
            } else {
                entity.updateEnabled(entry.getIsEnabled());
            }

            results.add(typePreferenceRepository.save(entity));
        }

        log.info("通知種別設定一括更新: userId={}, count={}", userId, request.getPreferences().size());
        return notificationMapper.toTypePreferenceResponseList(results);
    }

    /**
     * 指定ユーザーの指定スコープで通知が有効かどうかを判定する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 有効な場合 true
     */
    public boolean isNotificationEnabled(Long userId, String scopeType, Long scopeId) {
        return preferenceRepository.findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId)
                .map(NotificationPreferenceEntity::getIsEnabled)
                .orElse(true);
    }

    /**
     * 指定ユーザーの指定通知種別が有効かどうかを判定する。
     *
     * @param userId           ユーザーID
     * @param notificationType 通知種別
     * @return 有効な場合 true
     */
    public boolean isTypeEnabled(Long userId, String notificationType) {
        return typePreferenceRepository.findByUserIdAndNotificationType(userId, notificationType)
                .map(NotificationTypePreferenceEntity::getIsEnabled)
                .orElse(true);
    }
}
