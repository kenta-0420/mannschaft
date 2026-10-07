package com.mannschaft.app.activity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.ActivitySyncChange;
import com.mannschaft.app.activity.dto.ActivitySyncPreviewEntry;
import com.mannschaft.app.activity.dto.ActivitySyncSelection;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 活動ドメインの同期専用writer。共通段取り役のTX内で呼び出し、保護された結果項目に触れない。 */
@Service
@RequiredArgsConstructor
public class ActivityScheduleSyncService {
    private final ActivityResultRepository results;
    private final ActivityParticipantRepository participants;
    private final ActivityScopeAccessGuard access;
    private final ContentVisibilityChecker visibility;
    private final ActivityMapper mapper;
    private final ObjectMapper json;
    private final EntityManager em;

    public Long createDraft(ScheduleActivitySource source, Long userId) {
        ActivityScopeType type = ActivityScopeType.valueOf(source.scopeType());
        access.checkMembership(userId, type, source.scopeId());
        List<ActivityResultEntity> linked = results.findAllByScheduleIdOrderByIdAsc(source.id()).stream()
                .filter(a -> sameScope(a, source)).toList();
        if (!linked.isEmpty()) {
            return linked.stream().filter(a -> visible(a, userId)).findFirst().map(ActivityResultEntity::getId)
                    .orElseThrow(() -> new BusinessException(ActivityErrorCode.DUPLICATE_SCHEDULE_ACTIVITY));
        }
        ActivityScheduleValues values = ActivityScheduleValues.from(source);
        ActivityResultEntity entity = results.saveAndFlush(ActivityResultEntity.builder()
                .scopeType(type).scopeId(source.scopeId()).scheduleId(source.id()).createdBy(userId)
                .status(ActivityStatus.DRAFT).title(values.title()).activityDate(values.activityDate())
                .activityEndDate(values.activityEndDate()).activityTimeStart(values.activityTimeStart())
                .activityTimeEnd(values.activityTimeEnd())
                .scheduleSyncState(serialize(new ActivityScheduleSyncState(values, Set.of()))).build());
        for (Long participantId : source.attendingUserIds()) {
            participants.save(ActivityParticipantEntity.builder().activityResultId(entity.getId()).userId(participantId).build());
        }
        return entity.getId();
    }

    /** 通常POSTの入力を既存記録へ黙って置換しない。存在を秘匿した競合として返す。 */
    public void rejectExistingLink(ScheduleActivitySource source) {
        if (results.findAllByScheduleIdOrderByIdAsc(source.id()).stream().anyMatch(a -> sameScope(a, source))) {
            throw new BusinessException(ActivityErrorCode.DUPLICATE_SCHEDULE_ACTIVITY);
        }
    }

    public List<ActivityRecordResponse> linked(ScheduleActivitySource source, Long userId) {
        return results.findAllByScheduleIdOrderByIdAsc(source.id()).stream()
                .filter(a -> sameScope(a, source) && visible(a, userId)).map(mapper::toActivityRecordResponse).toList();
    }

    public List<ActivitySyncPreviewEntry> preview(List<ScheduleActivitySource> projected, Long userId, boolean lock) {
        List<ActivitySyncPreviewEntry> entries = new ArrayList<>();
        if (projected.isEmpty()) return entries;
        Map<Long, ScheduleActivitySource> sources = projected.stream().collect(Collectors.toMap(ScheduleActivitySource::id, Function.identity()));
        List<ActivityResultEntity> linked = results.lockScheduleLinksIn(sources.keySet());
        Set<Long> published = visibility.filterAccessible(ReferenceType.ACTIVITY_RESULT,
                linked.stream().filter(a -> a.getStatus() != ActivityStatus.DRAFT).map(ActivityResultEntity::getId).toList(), userId);
        Map<String, ScopePermission> permissions = new java.util.HashMap<>();
        for (ActivityResultEntity activity : linked) {
            ScheduleActivitySource source = sources.get(activity.getScheduleId());
            if (!sameScope(activity, source)) continue;
            var permission = permissions.computeIfAbsent(source.scopeType() + ":" + source.scopeId(), key -> permission(source, userId));
            boolean authorOrAdmin = Objects.equals(activity.getCreatedBy(), userId) || permission.admin();
            boolean canView = activity.getStatus() == ActivityStatus.DRAFT ? authorOrAdmin : published.contains(activity.getId());
            if (!permission.member() || !authorOrAdmin || !canView) continue;
            List<ActivitySyncChange> changes = changes(activity, ActivityScheduleValues.from(source));
            if (!changes.isEmpty()) entries.add(new ActivitySyncPreviewEntry(activity.getId(), activity.getVersion(), activity.getStatus().name(), changes));
        }
        return entries.stream().sorted(java.util.Comparator.comparing(ActivitySyncPreviewEntry::id)).toList();
    }

    private ScopePermission permission(ScheduleActivitySource source, Long userId) {
        ActivityScopeType type = ActivityScopeType.valueOf(source.scopeType());
        try { access.checkMembership(userId, type, source.scopeId()); }
        catch (BusinessException denied) { return new ScopePermission(false, false); }
        return new ScopePermission(true, access.isAdminOrAbove(userId, type, source.scopeId()));
    }

    private record ScopePermission(boolean member, boolean admin) {}

    /** 確認内容の対象集合・versionを検証してから、基本項目だけ変更する。 */
    public void validateConfirmation(List<ActivitySyncPreviewEntry> current, List<ActivitySyncSelection> selections) {
        if (selections == null) {
            if (current.stream().flatMap(e -> e.changes().stream()).anyMatch(c -> !c.automatic())) {
                throw new BusinessException(ActivityErrorCode.SYNC_CONFIRMATION_REQUIRED);
            }
            return;
        }
        Map<Long, ActivitySyncSelection> selected = selections.stream().collect(Collectors.toMap(ActivitySyncSelection::id,
                Function.identity(), (a, b) -> { throw malformed(); }));
        if (!current.stream().map(ActivitySyncPreviewEntry::id).collect(Collectors.toSet()).equals(selected.keySet())) throw stale();
        for (var entry : current) {
            var selection = selected.get(entry.id());
            if (!Objects.equals(entry.version(), selection.version())) throw new BusinessException(CommonErrorCode.COMMON_003);
            Set<String> fields = entry.changes().stream().map(ActivitySyncChange::field).collect(Collectors.toSet());
            if (selection.applyFields() == null || !fields.containsAll(selection.applyFields())
                    || new HashSet<>(selection.applyFields()).size() != selection.applyFields().size()) throw malformed();
        }
    }

    public void apply(List<ScheduleActivitySource> projected, Long userId, List<ActivitySyncPreviewEntry> current,
                      List<ActivitySyncSelection> selections) {
        Map<Long, ActivitySyncSelection> selected = null;
        if (selections != null) {
            selected = selections.stream().collect(Collectors.toMap(ActivitySyncSelection::id, Function.identity(),
                    (a, b) -> { throw malformed(); }));
            Set<Long> ids = current.stream().map(ActivitySyncPreviewEntry::id).collect(Collectors.toSet());
            if (!ids.equals(selected.keySet())) throw stale();
            for (var entry : current) {
                if (!Objects.equals(entry.version(), selected.get(entry.id()).version())) {
                    throw new BusinessException(CommonErrorCode.COMMON_003);
                }
            }
        } else if (current.stream().flatMap(e -> e.changes().stream()).anyMatch(c -> !c.automatic())) {
            throw new BusinessException(ActivityErrorCode.SYNC_CONFIRMATION_REQUIRED);
        }
        Map<Long, ScheduleActivitySource> sources = projected.stream()
                .collect(Collectors.toMap(ScheduleActivitySource::id, Function.identity()));
        for (var entry : current) {
            ActivityResultEntity activity = results.findById(entry.id()).orElseThrow(ActivityScheduleSyncService::stale);
            List<String> fields = selected == null ? entry.changes().stream().map(ActivitySyncChange::field).toList()
                    : selected.get(entry.id()).applyFields();
            Set<String> changed = entry.changes().stream().map(ActivitySyncChange::field).collect(Collectors.toSet());
            if (fields == null || !changed.containsAll(fields) || new HashSet<>(fields).size() != fields.size()) throw malformed();
            if (fields.isEmpty()) continue;
            var next = ActivityScheduleValues.from(sources.get(activity.getScheduleId()));
            Map<String, Object> merged = values(activity).fields();
            for (String field : fields) merged.put(field, next.fields().get(field));
            ActivityScheduleValues applied = fromFields(merged);
            validateTime(applied);
            // 本文・結果・添付・参加者・公開範囲は専用更新から意図的に除外する。
            activity.synchronizeBasicFields(applied.title(), applied.activityDate(), applied.activityEndDate(),
                    applied.activityTimeStart(), applied.activityTimeEnd());
            ActivityScheduleSyncState state = state(activity);
            if (state != null) {
                var baseline = state.baseline().fields();
                for (String field : fields) baseline.put(field, next.fields().get(field));
                activity.replaceScheduleSyncState(serialize(new ActivityScheduleSyncState(fromFields(baseline), state.manualFields())));
            }
        }
        results.flush();
    }

    void markManualEdits(ActivityResultEntity activity, UpdateActivityRequest request) {
        ActivityScheduleSyncState state = state(activity);
        if (state == null) return;
        var before = values(activity).fields();
        var after = new ActivityScheduleValues(request.getTitle(), request.getActivityDate(), request.getActivityEndDate(),
                request.getActivityTimeStart(), request.getActivityTimeEnd()).fields();
        Set<String> manual = new HashSet<>(state.manualFields());
        before.forEach((field, value) -> { if (!Objects.equals(value, after.get(field))) manual.add(field); });
        activity.replaceScheduleSyncState(serialize(new ActivityScheduleSyncState(state.baseline(), Set.copyOf(manual))));
    }

    private List<ActivitySyncChange> changes(ActivityResultEntity activity, ActivityScheduleValues next) {
        ActivityScheduleSyncState state = state(activity);
        Map<String, Object> current = values(activity).fields();
        List<ActivitySyncChange> changes = new ArrayList<>();
        next.fields().forEach((field, value) -> {
            if (!Objects.equals(current.get(field), value)) {
                boolean automatic = activity.getStatus() == ActivityStatus.DRAFT && state != null
                        && !state.manualFields().contains(field) && Objects.equals(current.get(field), state.baseline().fields().get(field));
                changes.add(new ActivitySyncChange(field, current.get(field), value, automatic));
            }
        });
        return changes;
    }

    private boolean visible(ActivityResultEntity a, Long userId) {
        try { access.checkMembership(userId, a.getScopeType(), a.getScopeId()); }
        catch (BusinessException denied) { return false; }
        if (a.getStatus() == ActivityStatus.DRAFT) {
            return Objects.equals(a.getCreatedBy(), userId) || access.isAdminOrAbove(userId, a.getScopeType(), a.getScopeId());
        }
        return visibility.canView(ReferenceType.ACTIVITY_RESULT, a.getId(), userId);
    }

    private boolean editable(ActivityResultEntity a, Long userId) {
        if (!visible(a, userId)) return false;
        return Objects.equals(a.getCreatedBy(), userId) || access.isAdminOrAbove(userId, a.getScopeType(), a.getScopeId());
    }

    private boolean sameScope(ActivityResultEntity a, ScheduleActivitySource source) {
        return a.getScopeType().name().equals(source.scopeType()) && Objects.equals(a.getScopeId(), source.scopeId());
    }

    private ActivityScheduleValues values(ActivityResultEntity a) {
        return new ActivityScheduleValues(a.getTitle(), a.getActivityDate(), a.getActivityEndDate(),
                a.getActivityTimeStart(), a.getActivityTimeEnd());
    }

    private ActivityScheduleValues fromFields(Map<String, Object> values) {
        return new ActivityScheduleValues((String) values.get("title"), (java.time.LocalDate) values.get("activityDate"),
                (java.time.LocalDate) values.get("activityEndDate"), (java.time.LocalTime) values.get("activityTimeStart"),
                (java.time.LocalTime) values.get("activityTimeEnd"));
    }

    public static void validateTime(ActivityScheduleValues value) {
        var endDate = value.activityEndDate() == null ? value.activityDate() : value.activityEndDate();
        if (endDate.isBefore(value.activityDate()) || (value.activityTimeStart() != null && value.activityTimeEnd() != null
                && endDate.atTime(value.activityTimeEnd()).isBefore(value.activityDate().atTime(value.activityTimeStart())))) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }
    }

    private ActivityScheduleSyncState state(ActivityResultEntity activity) {
        if (activity.getScheduleSyncState() == null) return null;
        try { return json.readValue(activity.getScheduleSyncState(), ActivityScheduleSyncState.class); }
        catch (JsonProcessingException failure) { throw stale(); }
    }

    private String serialize(ActivityScheduleSyncState state) {
        try { return json.writeValueAsString(state); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("予定同期基準の保存に失敗しました", failure); }
    }

    private static BusinessException stale() { return new BusinessException(ActivityErrorCode.SYNC_STATE_CONFLICT); }
    private static BusinessException malformed() { return new BusinessException(CommonErrorCode.COMMON_001); }
}
