package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminErrorCode;
import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.UpdateFeatureFlagRequest;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フィーチャーフラグサービス。フラグの取得・更新を担当する。
 * isEnabled() はValkey（Redis）キャッシュを参照し、キャッシュミス時にDB参照する。
 * updateFlag() 時にキャッシュを自動無効化する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final AdminMapper adminMapper;

    /**
     * 全フィーチャーフラグ一覧を取得する。
     */
    public List<FeatureFlagResponse> getAllFlags() {
        return adminMapper.toFeatureFlagResponseList(featureFlagRepository.findAll());
    }

    /**
     * フラグキーでフィーチャーフラグを取得する。
     */
    public FeatureFlagResponse getByKey(String flagKey) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.FEATURE_FLAG_NOT_FOUND));
        return adminMapper.toFeatureFlagResponse(entity);
    }

    /**
     * フィーチャーフラグを更新する。Valkeyキャッシュを自動無効化する。
     */
    @Transactional
    @CacheEvict(value = "featureFlags", key = "#flagKey")
    public FeatureFlagResponse updateFlag(String flagKey, UpdateFeatureFlagRequest req, Long userId) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.FEATURE_FLAG_NOT_FOUND));

        entity.updateFlag(req.getIsEnabled(), userId);
        if (req.getDescription() != null) {
            entity = entity.toBuilder().description(req.getDescription()).build();
        }
        entity = featureFlagRepository.save(entity);

        log.info("フィーチャーフラグ更新: key={}, enabled={}, userId={}", flagKey, req.getIsEnabled(), userId);
        return adminMapper.toFeatureFlagResponse(entity);
    }

    /**
     * フラグが有効かどうかを確認する。
     * Valkeyキャッシュを参照し、キャッシュミス時にDB参照する（TTL: RedisConfig既定の30分）。
     */
    @Cacheable(value = "featureFlags", key = "#flagKey")
    public boolean isEnabled(String flagKey) {
        return featureFlagRepository.findByFlagKey(flagKey)
                .map(FeatureFlagEntity::getIsEnabled)
                .orElse(false);
    }
}
