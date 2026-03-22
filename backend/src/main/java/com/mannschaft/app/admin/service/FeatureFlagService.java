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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フィーチャーフラグサービス。フラグの取得・更新を担当する。
 * Valkeyキャッシュ連携は将来実装時に追加予定。
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
     *
     * @return フラグ一覧
     */
    public List<FeatureFlagResponse> getAllFlags() {
        return adminMapper.toFeatureFlagResponseList(featureFlagRepository.findAll());
    }

    /**
     * フラグキーでフィーチャーフラグを取得する。
     *
     * @param flagKey フラグキー
     * @return フラグ情報
     */
    public FeatureFlagResponse getByKey(String flagKey) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.FEATURE_FLAG_NOT_FOUND));
        return adminMapper.toFeatureFlagResponse(entity);
    }

    /**
     * フィーチャーフラグを更新する。
     *
     * @param flagKey フラグキー
     * @param req     更新リクエスト
     * @param userId  更新者ID
     * @return 更新後のフラグ情報
     */
    @Transactional
    public FeatureFlagResponse updateFlag(String flagKey, UpdateFeatureFlagRequest req, Long userId) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> new BusinessException(AdminErrorCode.FEATURE_FLAG_NOT_FOUND));

        entity.updateFlag(req.getIsEnabled(), userId);
        if (req.getDescription() != null) {
            entity = entity.toBuilder().description(req.getDescription()).build();
        }
        entity = featureFlagRepository.save(entity);

        log.info("フィーチャーフラグ更新: key={}, enabled={}, userId={}", flagKey, req.getIsEnabled(), userId);
        // TODO: Valkeyキャッシュ更新
        return adminMapper.toFeatureFlagResponse(entity);
    }

    /**
     * フラグが有効かどうかを確認する。キャッシュ参照用。
     *
     * @param flagKey フラグキー
     * @return 有効ならtrue
     */
    public boolean isEnabled(String flagKey) {
        // TODO: Valkeyキャッシュ参照を優先し、キャッシュミス時にDB参照
        return featureFlagRepository.findByFlagKey(flagKey)
                .map(FeatureFlagEntity::getIsEnabled)
                .orElse(false);
    }
}
