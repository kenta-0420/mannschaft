package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.UnflagRequestStatus;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import com.mannschaft.app.moderation.repository.YabaiUnflagRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ヤバいやつ解除申請サービス。解除申請フローを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class YabaiUnflagService {

    private final YabaiUnflagRequestRepository unflagRepository;
    private final ModerationSettingsRepository settingsRepository;
    private final UserViolationService violationService;
    private final ModerationExtMapper mapper;

    /**
     * 解除申請を作成する。
     *
     * @param userId ユーザーID
     * @param reason 申請理由
     * @return 解除申請レスポンス
     */
    @Transactional
    public YabaiUnflagResponse createUnflagRequest(Long userId, String reason) {
        // ヤバいやつフラグ状態を確認
        if (!violationService.isYabaiUser(userId)) {
            throw new BusinessException(ModerationExtErrorCode.UNFLAG_NOT_ELIGIBLE);
        }
        // 保留中の申請がないか確認
        Optional<YabaiUnflagRequestEntity> latest =
                unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        if (latest.isPresent()) {
            YabaiUnflagRequestEntity last = latest.get();
            if (last.getStatus() == UnflagRequestStatus.PENDING) {
                throw new BusinessException(ModerationExtErrorCode.PENDING_REQUEST_EXISTS);
            }
            // 次回申請可能日時チェック
            if (last.getNextEligibleAt() != null && LocalDateTime.now().isBefore(last.getNextEligibleAt())) {
                throw new BusinessException(ModerationExtErrorCode.UNFLAG_NOT_ELIGIBLE);
            }
        }

        YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                .userId(userId)
                .reason(reason)
                .build();
        entity = unflagRepository.save(entity);

        log.info("ヤバいやつ解除申請作成: id={}, userId={}", entity.getId(), userId);
        return mapper.toYabaiUnflagResponse(entity);
    }

    /**
     * ユーザーの最新の解除申請状態を取得する。
     *
     * @param userId ユーザーID
     * @return 解除申請レスポンス
     */
    public YabaiUnflagResponse getLatestRequestStatus(Long userId) {
        YabaiUnflagRequestEntity entity = unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.UNFLAG_REQUEST_NOT_FOUND));
        return mapper.toYabaiUnflagResponse(entity);
    }

    /**
     * 解除申請をレビューする（SYSTEM_ADMIN用）。
     *
     * @param id         解除申請ID
     * @param status     新ステータス（ACCEPTED/REJECTED）
     * @param reviewNote レビューメモ
     * @param reviewerId レビュアーID
     * @return 更新後の解除申請レスポンス
     */
    @Transactional
    public YabaiUnflagResponse reviewUnflagRequest(Long id, String status, String reviewNote, Long reviewerId) {
        YabaiUnflagRequestEntity entity = unflagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.UNFLAG_REQUEST_NOT_FOUND));

        if (entity.getStatus() != UnflagRequestStatus.PENDING) {
            throw new BusinessException(ModerationExtErrorCode.UNFLAG_INVALID_STATUS);
        }

        UnflagRequestStatus newStatus = UnflagRequestStatus.valueOf(status);

        // 却下時は次回申請可能日時を設定
        LocalDateTime nextEligible = null;
        if (newStatus == UnflagRequestStatus.REJECTED) {
            int eligibleMonths = getIntSetting("yabai_unflag_eligible_months", 3);
            nextEligible = LocalDateTime.now().plusMonths(eligibleMonths);
        }

        entity.review(reviewerId, reviewNote, newStatus, nextEligible);
        unflagRepository.save(entity);

        log.info("解除申請レビュー: id={}, newStatus={}, reviewerId={}", id, newStatus, reviewerId);
        return mapper.toYabaiUnflagResponse(entity);
    }

    /**
     * 解除申請一覧を取得する（ページング付き）。
     *
     * @param pageable ページング情報
     * @return ページング済み解除申請一覧
     */
    public Page<YabaiUnflagResponse> getUnflagRequests(Pageable pageable) {
        return unflagRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(mapper::toYabaiUnflagResponse);
    }

    /**
     * PENDING状態の解除申請数を取得する。
     *
     * @return 件数
     */
    public long countPendingRequests() {
        return unflagRepository.countByStatus(UnflagRequestStatus.PENDING);
    }

    private int getIntSetting(String key, int defaultValue) {
        return settingsRepository.findBySettingKey(key)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.getSettingValue());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
