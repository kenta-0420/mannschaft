package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ViolationType;
import com.mannschaft.app.moderation.dto.UserViolationHistoryResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import com.mannschaft.app.moderation.repository.UserViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ユーザー違反サービス。違反記録・累積チェック・時効管理・ヤバいやつ判定を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserViolationService {

    private final UserViolationRepository violationRepository;
    private final ModerationSettingsRepository settingsRepository;
    private final ModerationExtMapper mapper;

    /**
     * ユーザーの違反履歴を取得する（統計情報付き）。
     *
     * @param userId ユーザーID
     * @return 違反履歴レスポンス
     */
    public UserViolationHistoryResponse getViolationHistory(Long userId) {
        List<UserViolationEntity> violations = violationRepository.findByUserIdOrderByCreatedAtDesc(userId);

        long activeWarnings = violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(
                userId, ViolationType.WARNING);
        long activeContentDeletes = violationRepository.countByUserIdAndViolationTypeAndIsActiveTrue(
                userId, ViolationType.CONTENT_DELETE);
        long totalActive = violationRepository.countByUserIdAndIsActiveTrue(userId);

        int yabaiThreshold = getIntSetting("yabai_violation_threshold", 3);
        boolean isYabai = totalActive >= yabaiThreshold;

        return new UserViolationHistoryResponse(
                userId,
                activeWarnings,
                activeContentDeletes,
                totalActive,
                isYabai,
                mapper.toViolationResponseList(violations)
        );
    }

    /**
     * ユーザーの有効な違反一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 違反レスポンス一覧
     */
    public List<ViolationResponse> getActiveViolations(Long userId) {
        List<UserViolationEntity> violations =
                violationRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(userId);
        return mapper.toViolationResponseList(violations);
    }

    /**
     * WARNING自主修正完了を処理する。
     *
     * @param actionId       アクションID
     * @param userId         ユーザーID
     * @param correctionNote 修正メモ
     * @return 更新後の違反レスポンス
     */
    @Transactional
    public ViolationResponse selfCorrect(Long actionId, Long userId, String correctionNote) {
        UserViolationEntity violation = violationRepository.findByActionId(actionId);
        if (violation == null || !violation.getUserId().equals(userId)) {
            throw new BusinessException(ModerationExtErrorCode.VIOLATION_NOT_FOUND);
        }

        if (violation.getViolationType() != ViolationType.WARNING) {
            throw new BusinessException(ModerationExtErrorCode.SELF_CORRECT_EXPIRED);
        }

        int selfCorrectDays = getIntSetting("self_correct_window_days", 7);
        LocalDateTime deadline = violation.getCreatedAt().plusDays(selfCorrectDays);
        if (LocalDateTime.now().isAfter(deadline)) {
            throw new BusinessException(ModerationExtErrorCode.SELF_CORRECT_EXPIRED);
        }

        violation.deactivate();
        violationRepository.save(violation);

        log.info("WARNING自主修正完了: actionId={}, userId={}, note={}", actionId, userId, correctionNote);
        return mapper.toViolationResponse(violation);
    }

    /**
     * ユーザーがヤバいやつ認定されているか判定する。
     *
     * @param userId ユーザーID
     * @return ヤバいやつならtrue
     */
    public boolean isYabaiUser(Long userId) {
        long totalActive = violationRepository.countByUserIdAndIsActiveTrue(userId);
        int threshold = getIntSetting("yabai_violation_threshold", 3);
        return totalActive >= threshold;
    }

    /**
     * 有効な違反総数を取得する。
     *
     * @return 件数
     */
    public long countActiveViolations() {
        return violationRepository.countByIsActiveTrue();
    }

    /**
     * 設定値を整数として取得する。
     */
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
