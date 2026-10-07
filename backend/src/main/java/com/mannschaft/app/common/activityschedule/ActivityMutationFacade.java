package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.service.ActivityDetailService;
import com.mannschaft.app.activity.service.ActivityResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 認可を共通層で先行し、同じTXで活動の版検証と更新を実行する。 */
@Service
@RequiredArgsConstructor
public class ActivityMutationFacade {
    private final ActivityDetailService details;
    private final ActivityResultService activities;
    private final ActivityMapper mapper;

    @Transactional
    public ActivityRecordResponse publish(Long id, Long userId, Long version) {
        details.requireEditPermission(id, userId);
        return activities.publishActivityVersioned(id, userId, version);
    }

    @Transactional
    public ActivityRecordResponse update(Long id, Long userId, UpdateActivityRequest request) {
        details.requireEditPermission(id, userId);
        return mapper.toActivityRecordResponse(activities.updateActivity(id, userId, request));
    }

    @Transactional
    public List<ActivityParticipantResponse> addParticipants(Long id, Long userId, AddParticipantsRequest request) {
        details.requireEditPermission(id, userId);
        return activities.addParticipants(id, userId, request);
    }

    @Transactional
    public List<ActivityParticipantResponse> removeParticipants(Long id, Long userId, RemoveParticipantsRequest request) {
        details.requireEditPermission(id, userId);
        return activities.removeParticipants(id, userId, request);
    }
}
