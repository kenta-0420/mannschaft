package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminErrorCode;
import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.PublicFeatureFlagResponse;
import com.mannschaft.app.admin.dto.UpdateFeatureFlagRequest;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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

    /** F09.14 第一陣。行が無い場合も FeatureFlagService は false を返すため fail-closed。 */
    public static final String F09_14_TIMELINE_PAID_DELIVERY_ENABLED =
            "F09_14_TIMELINE_PAID_DELIVERY_ENABLED";

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
     * 一般ユーザー向け公開フィーチャーフラグ一覧を取得する（Gate基盤工事①）。
     * flagKey / enabled のみを返し、description / updatedBy / id 等の管理者専用情報は含まない。
     */
    @Cacheable(value = "featureFlagsPublicList")
    public List<PublicFeatureFlagResponse> getPublicFlags() {
        return adminMapper.toPublicFeatureFlagResponseList(featureFlagRepository.findAll());
    }

    /**
     * フィーチャーフラグを更新する。Valkeyキャッシュ（単一キー・公開一覧の両方）を自動無効化する。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "featureFlags", key = "#flagKey"),
            @CacheEvict(value = "featureFlagsPublicList", allEntries = true)
    })
    public FeatureFlagResponse updateFlag(String flagKey, UpdateFeatureFlagRequest req, Long userId) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.FEATURE_FLAG_NOT_FOUND));

        entity.updateFlag(req.getIsEnabled(), userId);
        if (req.getDescription() != null) {
            // managed entity を直接ミューテート。toBuilder().build() は継承フィールド id を
            // 引き継がず id=null の新インスタンスになり、save が INSERT になって
            // flag_key 一意制約違反で 500 になるため使わない。
            entity.updateDescription(req.getDescription());
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
