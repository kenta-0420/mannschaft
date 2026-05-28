package com.mannschaft.app.navsettings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.navsettings.dto.NavFeatureResponse;
import com.mannschaft.app.navsettings.dto.NavSettingsResponse;
import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import com.mannschaft.app.navsettings.error.NavSettingsErrorCode;
import com.mannschaft.app.navsettings.repository.NavFeatureRepository;
import com.mannschaft.app.navsettings.repository.UserNavSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavSettingsService {

    private final NavFeatureRepository navFeatureRepository;
    private final UserNavSettingsRepository userNavSettingsRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public NavSettingsResponse getMyNavSettings(Long userId) {
        // is_enabled=TRUE の項目のみ取得
        List<NavFeatureEntity> features = navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc();

        // ユーザーの非表示キーを取得（レコードなければ空セット）
        Set<String> hiddenKeys = loadHiddenKeys(userId);

        List<NavFeatureResponse> responses = features.stream()
                .map(f -> NavFeatureResponse.from(f, isVisible(f, hiddenKeys)))
                .toList();

        return NavSettingsResponse.builder().features(responses).build();
    }

    @Transactional
    public void updateMyNavSettings(Long userId, List<String> hiddenNavKeys) {
        // 存在するキーをすべて取得して検証
        List<NavFeatureEntity> allEnabled = navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc();
        Set<String> validKeys = allEnabled.stream().map(NavFeatureEntity::getKey)
                .collect(java.util.stream.Collectors.toSet());

        for (String key : hiddenNavKeys) {
            // 存在チェック
            if (!validKeys.contains(key)) {
                throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004);
            }
            // 固定項目を非表示にしようとしていないかチェック
            allEnabled.stream()
                    .filter(f -> f.getKey().equals(key))
                    .filter(NavFeatureEntity::isFixed)
                    .findFirst()
                    .ifPresent(f -> { throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_002); });
        }

        // UPSERT
        try {
            String json = objectMapper.writeValueAsString(hiddenNavKeys);
            userNavSettingsRepository.upsertHiddenKeys(userId, json);
        } catch (Exception e) {
            log.error("ナビ設定の保存に失敗", e);
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004);
        }
    }

    private boolean isVisible(NavFeatureEntity feature, Set<String> hiddenKeys) {
        // 固定項目は常に表示
        if (feature.isFixed()) return true;
        // 非表示リストになければ表示
        return !hiddenKeys.contains(feature.getKey());
    }

    private Set<String> loadHiddenKeys(Long userId) {
        return userNavSettingsRepository.findById(userId)
                .map(entity -> {
                    try {
                        List<String> list = objectMapper.readValue(
                                entity.getHiddenNavKeys(),
                                new TypeReference<List<String>>() {});
                        return new java.util.HashSet<>(list);
                    } catch (Exception e) {
                        log.warn("hiddenNavKeys のパース失敗 userId={}", userId, e);
                        return new java.util.HashSet<String>();
                    }
                })
                .orElse(Collections.emptySet());
    }
}
