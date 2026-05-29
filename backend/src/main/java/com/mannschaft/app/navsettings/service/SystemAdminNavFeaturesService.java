package com.mannschaft.app.navsettings.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.navsettings.dto.NavFeatureAdminResponse;
import com.mannschaft.app.navsettings.dto.NavFeatureCreateRequest;
import com.mannschaft.app.navsettings.dto.NavFeatureUpdateRequest;
import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import com.mannschaft.app.navsettings.error.NavSettingsErrorCode;
import com.mannschaft.app.navsettings.repository.NavFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SystemAdminNavFeaturesService {

    private final NavFeatureRepository navFeatureRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<NavFeatureAdminResponse> listAll() {
        return navFeatureRepository.findAllByOrderBySortOrderAsc()
                .stream()
                .map(NavFeatureAdminResponse::from)
                .toList();
    }

    /**
     * ナビ項目を追加する。
     *
     * @param request     追加内容
     * @param actorUserId 操作者ユーザーID（監査ログ記録用）
     */
    @Transactional
    public NavFeatureAdminResponse create(NavFeatureCreateRequest request, Long actorUserId) {
        if (navFeatureRepository.existsById(request.getKey())) {
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_003);
        }
        NavFeatureEntity entity = new NavFeatureEntity();
        entity.setKey(request.getKey());
        entity.setLabelKey(request.getLabelKey());
        entity.setIcon(request.getIcon());
        entity.setPath(request.getPath());
        entity.setFixed(request.getFixed());
        entity.setEnabled(request.getEnabled());
        entity.setSubscriptionRequired(request.getSubscriptionRequired());
        entity.setSortOrder(request.getSortOrder());
        entity.setMobileVisible(request.getMobileVisible());
        NavFeatureAdminResponse response = NavFeatureAdminResponse.from(navFeatureRepository.save(entity));

        // 監査ログ記録（保存成功後のみ）
        recordFeatureAudit(AuditEventType.NAV_FEATURE_CREATED, actorUserId, request.getKey());
        return response;
    }

    /**
     * ナビ項目を更新する。
     *
     * @param key         対象キー
     * @param request     更新内容
     * @param actorUserId 操作者ユーザーID（監査ログ記録用）
     */
    @Transactional
    public NavFeatureAdminResponse update(String key, NavFeatureUpdateRequest request, Long actorUserId) {
        NavFeatureEntity entity = navFeatureRepository.findById(key)
                .orElseThrow(() -> new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004));

        // is_fixed=TRUE の項目の固定解除を禁止
        if (entity.isFixed() && Boolean.FALSE.equals(request.getFixed())) {
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_001);
        }

        entity.setLabelKey(request.getLabelKey());
        entity.setIcon(request.getIcon());
        entity.setPath(request.getPath());
        entity.setFixed(request.getFixed());
        entity.setEnabled(request.getEnabled());
        entity.setSubscriptionRequired(request.getSubscriptionRequired());
        entity.setSortOrder(request.getSortOrder());
        entity.setMobileVisible(request.getMobileVisible());
        NavFeatureAdminResponse response = NavFeatureAdminResponse.from(navFeatureRepository.save(entity));

        // 監査ログ記録（保存成功後のみ）
        recordFeatureAudit(AuditEventType.NAV_FEATURE_UPDATED, actorUserId, key);
        return response;
    }

    /**
     * ナビ項目を削除する。
     *
     * @param key         対象キー
     * @param actorUserId 操作者ユーザーID（監査ログ記録用）
     */
    @Transactional
    public void delete(String key, Long actorUserId) {
        NavFeatureEntity entity = navFeatureRepository.findById(key)
                .orElseThrow(() -> new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004));
        if (entity.isFixed()) {
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_001);
        }
        navFeatureRepository.delete(entity);

        // 監査ログ記録（削除成功後のみ）
        recordFeatureAudit(AuditEventType.NAV_FEATURE_DELETED, actorUserId, key);
    }

    /**
     * ナビ項目操作の監査ログを記録する。
     * key は ^[a-z0-9\-]+$ に制約されているため JSON エスケープ不要。
     */
    private void recordFeatureAudit(AuditEventType eventType, Long actorUserId, String key) {
        String metadata = String.format("{\"source\":\"NAV_FEATURE\",\"key\":\"%s\"}", key);
        auditLogService.record(eventType.name(),
                actorUserId, null, null, null, null, null, null, metadata);
    }
}
