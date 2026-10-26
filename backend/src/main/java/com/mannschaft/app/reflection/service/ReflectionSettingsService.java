package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.dto.ReflectionSettingsResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionSettingsRequest;
import com.mannschaft.app.reflection.entity.UserReflectionSettingsEntity;
import com.mannschaft.app.reflection.repository.UserReflectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 想起通知設定のサービス（F06.5・§2.7 / §7 #14〜#15）。
 *
 * <p>未設定ユーザーは既定 {@link ReflectionConstants#DEFAULT_REMIND_HOUR} 時。UPSERT で更新する。
 * {@link #remindHour} は remind_at 生成（§5.3）で使用する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionSettingsService {

    private final UserReflectionSettingsRepository userReflectionSettingsRepository;

    /** 想起通知設定取得（§7 #14・未設定は既定 8 時）。 */
    @Transactional(readOnly = true)
    public ReflectionSettingsResponse getSettings(Long userId) {
        int hour = remindHour(userId);
        return ReflectionSettingsResponse.builder().remindHour(hour).build();
    }

    /** 想起通知設定更新（§7 #15・UPSERT・0-23 検証は Bean Validation＝AC-23）。 */
    @Transactional
    public ReflectionSettingsResponse updateSettings(Long userId, UpdateReflectionSettingsRequest request) {
        UserReflectionSettingsEntity entity = userReflectionSettingsRepository.findById(userId)
                .orElse(null);
        if (entity == null) {
            entity = UserReflectionSettingsEntity.builder()
                    .userId(userId)
                    .remindHour(request.remindHour())
                    .build();
        } else {
            entity.updateRemindHour(request.remindHour());
        }
        UserReflectionSettingsEntity saved = userReflectionSettingsRepository.save(entity);
        return ReflectionSettingsResponse.builder().remindHour(saved.getRemindHour()).build();
    }

    /**
     * 想起通知時刻を解決する（remind_at 生成で使用・§5.3）。未設定ユーザーは既定 8 時。
     *
     * @param userId ユーザーID
     * @return 0-23 の時刻
     */
    @Transactional(readOnly = true)
    public int remindHour(Long userId) {
        return userReflectionSettingsRepository.findById(userId)
                .map(UserReflectionSettingsEntity::getRemindHour)
                .orElse(ReflectionConstants.DEFAULT_REMIND_HOUR);
    }
}
