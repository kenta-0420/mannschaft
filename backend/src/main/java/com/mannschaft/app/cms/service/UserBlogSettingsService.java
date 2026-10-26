package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.UpdateBlogSettingsRequest;
import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import com.mannschaft.app.cms.repository.UserBlogSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ユーザーブログ設定サービス。セルフレビュー設定の管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserBlogSettingsService {

    private final UserBlogSettingsRepository settingsRepository;
    private final CmsMapper cmsMapper;

    /**
     * ブログ設定を取得する。存在しない場合はデフォルト設定を作成する。
     */
    @Transactional
    public BlogSettingsResponse getOrCreateSettings(Long userId) {
        UserBlogSettingsEntity entity = settingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserBlogSettingsEntity newSettings = UserBlogSettingsEntity.builder()
                            .userId(userId)
                            .build();
                    return settingsRepository.save(newSettings);
                });
        return cmsMapper.toBlogSettingsResponse(entity);
    }

    /**
     * ブログ設定を更新する。
     */
    @Transactional
    public BlogSettingsResponse updateSettings(Long userId, UpdateBlogSettingsRequest request) {
        UserBlogSettingsEntity entity = settingsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserBlogSettingsEntity newSettings = UserBlogSettingsEntity.builder()
                            .userId(userId)
                            .build();
                    return settingsRepository.save(newSettings);
                });

        entity.update(
                request.getSelfReviewEnabled() != null ? request.getSelfReviewEnabled() : entity.getSelfReviewEnabled(),
                request.getSelfReviewStart() != null ? request.getSelfReviewStart() : entity.getSelfReviewStart(),
                request.getSelfReviewEnd() != null ? request.getSelfReviewEnd() : entity.getSelfReviewEnd()
        );

        UserBlogSettingsEntity saved = settingsRepository.save(entity);
        log.info("ブログ設定更新: userId={}", userId);
        return cmsMapper.toBlogSettingsResponse(saved);
    }
}
