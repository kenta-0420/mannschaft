package com.mannschaft.app.digest.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.digest.DigestErrorCode;
import com.mannschaft.app.digest.DigestMapper;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStyle;
import com.mannschaft.app.digest.ScheduleType;
import com.mannschaft.app.digest.dto.DigestConfigRequest;
import com.mannschaft.app.digest.dto.DigestConfigResponse;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.repository.TimelineDigestConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * ダイジェスト自動生成設定の CRUD サービス。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DigestConfigService {

    private final TimelineDigestConfigRepository configRepository;
    private final DigestMapper digestMapper;
    private final DigestProperties digestProperties;

    /**
     * スコープの自動生成設定を取得する。
     *
     * @throws BusinessException 設定が存在しない場合（DIGEST_014）
     */
    public DigestConfigResponse getConfig(String scopeType, Long scopeId) {
        DigestScopeType scope = DigestScopeType.valueOf(scopeType);
        TimelineDigestConfigEntity config = configRepository
                .findByScopeTypeAndScopeId(scope, scopeId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_014));
        return digestMapper.toConfigResponse(config);
    }

    /**
     * 自動生成設定を作成または更新する。
     * 同一スコープに有効な設定が既に存在する場合は更新する。
     *
     * @throws BusinessException バリデーションエラー
     */
    /**
     * 設定が新規作成されたかどうかを含むレスポンス。
     */
    public record ConfigSaveResult(DigestConfigResponse response, boolean created) {}

    @Transactional
    public ConfigSaveResult createOrUpdateConfig(DigestConfigRequest request, Long userId) {
        DigestScopeType scopeType = DigestScopeType.valueOf(request.getScopeType());
        ScheduleType scheduleType = ScheduleType.valueOf(request.getScheduleType());
        DigestStyle digestStyle = DigestStyle.valueOf(request.getDigestStyle());

        // バリデーション
        validateTimezone(request.getTimezone());
        validateSchedule(scheduleType, request.getScheduleTime(), request.getScheduleDayOfWeek());

        Optional<TimelineDigestConfigEntity> existing = configRepository
                .findByScopeTypeAndScopeId(scopeType, request.getScopeId());

        TimelineDigestConfigEntity config;
        if (existing.isPresent()) {
            // 更新
            config = existing.get().toBuilder()
                    .scheduleType(scheduleType)
                    .scheduleTime(request.getScheduleTime())
                    .scheduleDayOfWeek(request.getScheduleDayOfWeek())
                    .digestStyle(digestStyle)
                    .autoPublish(request.getAutoPublish() != null ? request.getAutoPublish() : false)
                    .stylePresets(request.getStylePresets())
                    .includeReactions(request.getIncludeReactions() != null ? request.getIncludeReactions() : true)
                    .includePolls(request.getIncludePolls() != null ? request.getIncludePolls() : true)
                    .includeDiffFromPrevious(request.getIncludeDiffFromPrevious() != null ? request.getIncludeDiffFromPrevious() : false)
                    .minPostsThreshold(request.getMinPostsThreshold() != null ? request.getMinPostsThreshold() : digestProperties.getDefaults().getMinPostsThreshold())
                    .maxPostsPerDigest(request.getMaxPostsPerDigest() != null ? request.getMaxPostsPerDigest() : digestProperties.getDefaults().getMaxPostsPerDigest())
                    .timezone(request.getTimezone())
                    .contentMaxChars(request.getContentMaxChars() != null ? request.getContentMaxChars() : digestProperties.getDefaults().getContentMaxChars())
                    .language(request.getLanguage() != null ? request.getLanguage() : "ja")
                    .customPromptSuffix(request.getCustomPromptSuffix())
                    .autoTagIds(request.getAutoTagIds() != null ? request.getAutoTagIds().toString() : null)
                    .build();
        } else {
            // 新規作成
            config = TimelineDigestConfigEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(request.getScopeId())
                    .scheduleType(scheduleType)
                    .scheduleTime(request.getScheduleTime())
                    .scheduleDayOfWeek(request.getScheduleDayOfWeek())
                    .digestStyle(digestStyle)
                    .autoPublish(request.getAutoPublish() != null ? request.getAutoPublish() : false)
                    .stylePresets(request.getStylePresets())
                    .includeReactions(request.getIncludeReactions() != null ? request.getIncludeReactions() : true)
                    .includePolls(request.getIncludePolls() != null ? request.getIncludePolls() : true)
                    .includeDiffFromPrevious(request.getIncludeDiffFromPrevious() != null ? request.getIncludeDiffFromPrevious() : false)
                    .minPostsThreshold(request.getMinPostsThreshold() != null ? request.getMinPostsThreshold() : digestProperties.getDefaults().getMinPostsThreshold())
                    .maxPostsPerDigest(request.getMaxPostsPerDigest() != null ? request.getMaxPostsPerDigest() : digestProperties.getDefaults().getMaxPostsPerDigest())
                    .timezone(request.getTimezone())
                    .contentMaxChars(request.getContentMaxChars() != null ? request.getContentMaxChars() : digestProperties.getDefaults().getContentMaxChars())
                    .language(request.getLanguage() != null ? request.getLanguage() : "ja")
                    .customPromptSuffix(request.getCustomPromptSuffix())
                    .autoTagIds(request.getAutoTagIds() != null ? request.getAutoTagIds().toString() : null)
                    .isEnabled(true)
                    .createdBy(userId)
                    .build();
        }

        boolean isNew = existing.isEmpty();
        TimelineDigestConfigEntity saved = configRepository.save(config);
        log.info("ダイジェスト設定を{}しました: scope={}:{}, scheduleType={}",
                isNew ? "作成" : "更新",
                scopeType, request.getScopeId(), scheduleType);
        return new ConfigSaveResult(digestMapper.toConfigResponse(saved), isNew);
    }

    /**
     * 自動生成設定を論理削除する。
     *
     * @throws BusinessException 設定が存在しない場合（DIGEST_014）
     */
    @Transactional
    public void deleteConfig(String scopeType, Long scopeId) {
        DigestScopeType scope = DigestScopeType.valueOf(scopeType);
        TimelineDigestConfigEntity config = configRepository
                .findByScopeTypeAndScopeId(scope, scopeId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_014));

        TimelineDigestConfigEntity deleted = config.toBuilder()
                .deletedAt(LocalDateTime.now())
                .isEnabled(false)
                .build();
        configRepository.save(deleted);
        log.info("ダイジェスト設定を無効化しました: scope={}:{}", scopeType, scopeId);
    }

    /**
     * タイムゾーンのバリデーション。
     */
    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            throw new BusinessException(DigestErrorCode.DIGEST_022);
        }
    }

    /**
     * スケジュール設定のバリデーション。
     */
    private void validateSchedule(ScheduleType scheduleType, java.time.LocalTime scheduleTime, Integer dayOfWeek) {
        if (scheduleType != ScheduleType.MANUAL && scheduleTime == null) {
            throw new BusinessException(DigestErrorCode.DIGEST_023);
        }
        if (scheduleType == ScheduleType.WEEKLY) {
            if (dayOfWeek == null || dayOfWeek < 0 || dayOfWeek > 6) {
                throw new BusinessException(DigestErrorCode.DIGEST_016);
            }
        }
    }
}
