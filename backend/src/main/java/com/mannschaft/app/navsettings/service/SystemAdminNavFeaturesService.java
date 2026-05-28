package com.mannschaft.app.navsettings.service;

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

    @Transactional(readOnly = true)
    public List<NavFeatureAdminResponse> listAll() {
        return navFeatureRepository.findAllByOrderBySortOrderAsc()
                .stream()
                .map(NavFeatureAdminResponse::from)
                .toList();
    }

    @Transactional
    public NavFeatureAdminResponse create(NavFeatureCreateRequest request) {
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
        return NavFeatureAdminResponse.from(navFeatureRepository.save(entity));
    }

    @Transactional
    public NavFeatureAdminResponse update(String key, NavFeatureUpdateRequest request) {
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
        return NavFeatureAdminResponse.from(navFeatureRepository.save(entity));
    }

    @Transactional
    public void delete(String key) {
        NavFeatureEntity entity = navFeatureRepository.findById(key)
                .orElseThrow(() -> new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004));
        if (entity.isFixed()) {
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_001);
        }
        navFeatureRepository.delete(entity);
    }
}
