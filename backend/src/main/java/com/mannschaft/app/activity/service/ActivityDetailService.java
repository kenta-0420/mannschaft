package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.dto.ActivityDetailResponse;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivitySourceScheduleResponse;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.service.ScheduleActivitySourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 認証済み詳細に表示用情報を追加する。公開DTO・一覧DTOは変更しない。 */
@Service
@RequiredArgsConstructor
public class ActivityDetailService {
    private final ActivityResultService activityService;
    private final ActivityScopeAccessGuard scopeAccessGuard;
    private final ActivityMapper mapper;
    private final ActivityParticipantRepository participantRepository;
    private final ActivityTemplateService templateService;
    private final NameResolverService nameResolver;
    private final ScheduleActivitySourceService scheduleSources;

    /** 共通段取り役の更新認可。所属・閲覧認可の後に作者／管理者を検証する。 */
    public void requireEditPermission(Long id, Long userId) {
        var activity = activityService.getActivity(id, userId);
        scopeAccessGuard.checkAuthorOrAdmin(userId, activity.getCreatedBy(), activity.getScopeType(), activity.getScopeId());
    }

    public ActivityDetailResponse getDetail(Long id, Long userId) {
        ActivityResultEntity activity = activityService.getActivity(id, userId);
        boolean canEdit = Objects.equals(activity.getCreatedBy(), userId)
                || scopeAccessGuard.isAdminOrAbove(userId, activity.getScopeType(), activity.getScopeId());
        var participants = participantRepository.findByActivityResultIdOrderByCreatedAtAsc(id);
        var names = nameResolver.resolveUserDisplayNames(participants.stream().map(p -> p.getUserId()).toList());
        List<ActivityParticipantResponse> participantResponses = participants.stream()
                .map(p -> new ActivityParticipantResponse(p.getId(), p.getUserId(), names.get(p.getUserId()),
                        null, p.getRoleLabel(), p.getCreatedAt())).toList();
        ActivitySourceScheduleResponse source = activity.getScheduleId() == null ? null
                : scheduleSources.visibleSource(activity.getScheduleId(), userId)
                .filter(s -> activity.getScopeType().name().equals(s.scopeType()) && Objects.equals(activity.getScopeId(), s.scopeId()))
                .map(s -> new ActivitySourceScheduleResponse(s.scopeType(), s.scopeId(),
                        nameResolver.resolveScopeSlug(s.scopeType(), s.scopeId()), s.id(),
                        "CANCELLED".equals(s.status()) ? "CANCELLED" : "AVAILABLE", true))
                .orElse(new ActivitySourceScheduleResponse(null, null, null, null, "UNAVAILABLE", false));
        var fields = activity.getTemplateId() == null ? List.<com.mannschaft.app.activity.dto.ActivityTemplateResponse.TemplateFieldResponse>of()
                : templateService.fieldsIfPresent(activity.getTemplateId(), activity.getScopeType(), activity.getScopeId());
        return new ActivityDetailResponse(mapper.toActivityRecordResponse(activity),
                nameResolver.resolveScopeSlug(activity.getScopeType().name(), activity.getScopeId()), activity.getActivityEndDate(),
                activity.getVersion(), canEdit, canEdit && activity.getStatus() == ActivityStatus.DRAFT,
                source, participantResponses, fields);
    }
}
