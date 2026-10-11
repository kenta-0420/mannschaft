package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityDetailResponse;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.ActivitySyncPreviewRequest;
import com.mannschaft.app.activity.dto.ActivitySyncPreviewResponse;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.ExpectedScheduleEntry;
import com.mannschaft.app.activity.dto.ExpectedScheduleState;
import com.mannschaft.app.activity.service.ActivityDetailService;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.activity.service.ActivityScheduleSyncService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleActivitySourceService;
import com.mannschaft.app.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** 予定と活動の原子性が必要な操作だけを共通層で段取りする。両ドメインのTXはこのTXへ参加する。 */
@Service
@RequiredArgsConstructor
public class ActivityScheduleFacade {
    private final ScheduleActivitySourceService sources;
    private final ScheduleService schedules;
    private final ActivityScheduleSyncService synchronization;
    private final ActivityScheduleCreationFacade creation;
    private final com.mannschaft.app.activity.service.AutomaticActivityListService automaticLists;
    private final AutomaticScheduleCompletionFacade completion;

    @Transactional
    public ActivityDetailResponse draft(Long scheduleId, String scopeType, Long scopeId, Long userId) {
        return creation.draft(scheduleId, scopeType, scopeId, userId);
    }

    /** 従来作成経路も同じ予定ロックを共有し、別スコープの任意ID関連付けを防ぐ。 */
    @Transactional
    public ActivityRecordResponse create(ActivityScopeType type, Long scopeId, Long userId, CreateActivityRequest request) {
        return creation.create(type, scopeId, userId, request);
    }

    @Transactional(readOnly = true)
    public List<ActivityRecordResponse> linked(Long scheduleId, String type, Long scopeId, Long userId) {
        var source = sources.requireSource(scheduleId, type, scopeId, userId, false);
        var records = new java.util.LinkedHashMap<Long, ActivityRecordResponse>();
        synchronization.linked(source, userId).forEach(a -> records.put(a.getId(), a));
        automaticLists.linkedMetadata(source, userId).forEach(a -> records.putIfAbsent(a.getId(), a));
        return List.copyOf(records.values());
    }

    @Transactional
    public ActivitySyncPreviewResponse preview(Long scheduleId, String type, Long scopeId, Long userId,
                                               ActivitySyncPreviewRequest request) {
        sources.requireSource(scheduleId, type, scopeId, userId, false);
        schedules.checkScheduleManagementAccess(scheduleId, userId);
        String updateScope = request.updateScope() == null ? "THIS_ONLY" : request.updateScope();
        sources.lockSeries(scheduleId);
        var projected = sources.projectedSources(scheduleId, request.scheduleUpdate(), updateScope);
        var current = sources.currentSources(projected.stream().map(ScheduleActivitySource::id).toList(), true);
        var selected = sources.currentSources(List.of(scheduleId), true).getFirst();
        return new ActivitySyncPreviewResponse(state(selected, current), synchronization.preview(projected, userId, false));
    }

    @Transactional
    public ScheduleResponse update(Long scheduleId, String type, Long scopeId, Long userId,
                                    UpdateScheduleRequest request, String updateScope) {
        // 既存PATCHはURLではなく予定の実スコープで認可する契約を保つ。新しい参照入口の厳格照合とは分ける。
        schedules.checkScheduleManagementAccess(scheduleId, userId);
        var actualSource = sources.sourceForAuthorizedUpdate(scheduleId);
        if ("PERSONAL".equals(actualSource.scopeType())) {
            // 個人予定は活動同期の対象外。従来の所有者認可と更新処理だけを利用する。
            return schedules.updateSchedule(scheduleId, request, updateScope, userId);
        }
        sources.lockSeries(scheduleId);
        var projected = sources.projectedSources(scheduleId, request, updateScope);
        var current = sources.currentSources(projected.stream().map(ScheduleActivitySource::id).toList(), true);
        var selected = sources.currentSources(List.of(scheduleId), true).getFirst();
        var confirmation = request.getSyncConfirmation();
        if (confirmation != null && !Objects.equals(state(selected, current), normalize(confirmation.expectedScheduleState()))) {
            throw new BusinessException(ActivityErrorCode.SYNC_STATE_CONFLICT);
        }
        var activityChanges = synchronization.preview(projected, userId, true);
        // 先に検証し、予定保存・活動同期の途中失敗は外側TXで一緒に巻き戻す。
        synchronization.validateConfirmation(activityChanges, confirmation == null ? null : confirmation.activities());
        ScheduleResponse response = schedules.updateSchedule(scheduleId, request, updateScope, userId);
        synchronization.apply(projected, userId, activityChanges, confirmation == null ? null : confirmation.activities());
        completion.reopenFuture(projected.stream().map(ScheduleActivitySource::id).toList());
        // helperのflush/rowlock再確認後のstatusを、保存前に組み立てられたDTOへ反映する。
        var latest = sources.currentSources(List.of(scheduleId), false).getFirst();
        if (!Objects.equals(response.getContent().status(), latest.status())) {
            var content = response.getContent();
            response = response.toBuilder().content(new ScheduleResponse.ScheduleContentDto(content.title(),
                    latest.status(), content.eventType(), content.location(), content.attendanceRequired())).build();
        }
        return response;
    }

    private ExpectedScheduleState state(ScheduleActivitySource selected, List<ScheduleActivitySource> all) {
        return new ExpectedScheduleState(second(selected.updatedAt()), selected.title(), second(selected.startAt()),
                second(selected.endAt()), selected.allDay(), selected.status(), all.stream()
                .map(s -> new ExpectedScheduleEntry(s.id(), second(s.updatedAt()), s.title(), second(s.startAt()),
                        second(s.endAt()), s.allDay(), s.status()))
                .sorted(java.util.Comparator.comparing(ExpectedScheduleEntry::id)).toList());
    }

    private ExpectedScheduleState normalize(ExpectedScheduleState value) {
        if (value == null || value.schedules() == null) throw new BusinessException(ActivityErrorCode.SYNC_STATE_CONFLICT);
        return new ExpectedScheduleState(second(value.updatedAt()), value.title(), second(value.startAt()), second(value.endAt()),
                value.allDay(), value.status(), value.schedules().stream()
                .map(s -> new ExpectedScheduleEntry(s.id(), second(s.updatedAt()), s.title(), second(s.startAt()), second(s.endAt()), s.allDay(), s.status()))
                .sorted(java.util.Comparator.comparing(ExpectedScheduleEntry::id)).toList());
    }

    private OffsetDateTime second(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE)
                .toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
    }
}
