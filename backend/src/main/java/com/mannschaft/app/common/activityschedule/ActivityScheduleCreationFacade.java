package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityDetailResponse;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.service.ActivityDetailService;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.activity.service.ActivityScheduleSyncService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.service.ScheduleActivitySourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 手動作成の予定lockと活動保存を同じTXで段取りする。自動生成とは権限・履歴抑止を分ける。 */
@Service
@RequiredArgsConstructor
public class ActivityScheduleCreationFacade {
    private final ScheduleActivitySourceService sources;
    private final ActivityScheduleSyncService synchronization;
    private final ActivityDetailService details;
    private final ActivityResultService activities;
    private final ActivityMapper mapper;

    @Transactional
    public ActivityDetailResponse draft(Long scheduleId, String scopeType, Long scopeId, Long userId) {
        if (!java.util.Set.of("TEAM", "ORGANIZATION").contains(scopeType)) {
            throw new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_001);
        }
        var source = sources.requireSource(scheduleId, scopeType, scopeId, userId, true);
        return details.getDetail(synchronization.createDraft(source, userId), userId);
    }

    @Transactional
    public ActivityRecordResponse create(ActivityScopeType type, Long scopeId, Long userId, CreateActivityRequest request) {
        if (request.getScheduleId() != null) {
            var source = sources.requireSource(request.getScheduleId(), type.name(), scopeId, userId, true);
            synchronization.rejectExistingLink(source);
        }
        return mapper.toActivityRecordResponse(activities.createActivity(userId, type, scopeId, request));
    }
}
