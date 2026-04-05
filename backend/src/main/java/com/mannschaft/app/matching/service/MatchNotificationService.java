package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.ActivityType;
import com.mannschaft.app.matching.MatchCategory;
import com.mannschaft.app.matching.dto.NotificationPreferenceResponse;
import com.mannschaft.app.matching.dto.UpdateNotificationPreferenceRequest;
import com.mannschaft.app.matching.entity.MatchNotificationPreferenceEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchNotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * マッチング推薦通知設定サービス。通知設定の取得・更新を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchNotificationService {

    private final MatchNotificationPreferenceRepository preferenceRepository;
    private final MatchingMapper matchingMapper;

    /**
     * 通知設定を取得する。設定未作成の場合はデフォルト値を返す。
     */
    public NotificationPreferenceResponse getPreference(Long teamId) {
        return preferenceRepository.findByTeamId(teamId)
                .map(matchingMapper::toNotificationPreferenceResponse)
                .orElse(new NotificationPreferenceResponse(null, null, null, null, false));
    }

    /**
     * 通知設定を更新する（UPSERT）。
     */
    @Transactional
    public NotificationPreferenceResponse updatePreference(Long teamId, UpdateNotificationPreferenceRequest request) {
        ActivityType activityType = request.getActivityType() != null
                ? ActivityType.valueOf(request.getActivityType()) : null;
        MatchCategory category = request.getCategory() != null
                ? MatchCategory.valueOf(request.getCategory()) : null;
        Boolean isEnabled = request.getIsEnabled() != null ? request.getIsEnabled() : true;

        MatchNotificationPreferenceEntity entity = preferenceRepository.findByTeamId(teamId)
                .orElse(MatchNotificationPreferenceEntity.builder()
                        .teamId(teamId)
                        .build());

        entity.update(request.getPrefectureCode(), request.getCityCode(), activityType, category, isEnabled);

        MatchNotificationPreferenceEntity saved = preferenceRepository.save(entity);
        log.info("通知設定更新: teamId={}", teamId);
        return matchingMapper.toNotificationPreferenceResponse(saved);
    }
}
