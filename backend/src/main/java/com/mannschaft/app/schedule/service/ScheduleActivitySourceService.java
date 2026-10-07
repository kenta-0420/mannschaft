package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 元予定の実スコープ・可視性・ロックと基本項目の投影を担う。TXは共通段取り役が所有する。 */
@Service
@RequiredArgsConstructor
public class ScheduleActivitySourceService {
    private final ScheduleRepository scheduleRepository;
    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleRecurrenceService recurrenceService;
    private final ContentVisibilityChecker visibilityChecker;
    private final EntityManager entityManager;

    /** 参照の存在も権限も検証する。URLのスコープを信用せず実行から比較する。 */
    public ScheduleActivitySource requireSource(Long id, String scopeType, Long scopeId, Long userId,
                                                boolean lock) {
        if (lock) {
            List<ScheduleEntity> locked = scheduleRepository.lockActivitySources(List.of(id));
            if (locked.isEmpty()) throw new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
            entityManager.refresh(locked.getFirst(), LockModeType.PESSIMISTIC_WRITE);
        }
        ScheduleEntity source = findSource(id);
        ScheduleActivitySource value = toSource(source, lock);
        if (!Objects.equals(scopeType, value.scopeType()) || !Objects.equals(scopeId, value.scopeId())) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }
        visibilityChecker.assertCanView(ReferenceType.SCHEDULE, id, userId);
        return value;
    }

    /** 詳細の元予定参照。不可視・削除済みは値を返さない。 */
    public java.util.Optional<ScheduleActivitySource> visibleSource(Long id, Long userId) {
        if (!visibilityChecker.canView(ReferenceType.SCHEDULE, id, userId)) return java.util.Optional.empty();
        return scheduleRepository.findById(id).map(s -> toSource(s, false));
    }

    /** 既存繰返し更新の対象選択と日時移動をそのまま利用し、保存せず投影する。 */
    public List<ScheduleActivitySource> projectedSources(Long id, UpdateScheduleRequest request, String scope) {
        ScheduleEntity selected = findSource(id);
        return recurrenceService.previewActivitySources(selected, request, scope).stream()
                .map(s -> toSource(s, false)).toList();
    }

    /** 親と子のcurrent readを先に確定し、RRの古い集合と兄弟追加のphantomを避ける。 */
    public void lockSeries(Long id) {
        ScheduleEntity selected = findSource(id);
        if (!selected.isRecurring() && selected.getParentScheduleId() == null) {
            scheduleRepository.lockActivitySources(List.of(id));
            entityManager.refresh(selected, LockModeType.PESSIMISTIC_WRITE);
            return;
        }
        Long parentId = selected.getParentScheduleId() == null ? id : selected.getParentScheduleId();
        scheduleRepository.lockActivitySources(List.of(parentId));
        scheduleRepository.lockActivitySeriesChildren(parentId);
        // 認可読取ですでに管理されていた選択行だけ再読込する。他行はlocking queryが取得する。
        entityManager.refresh(selected, LockModeType.PESSIMISTIC_WRITE);
    }

    public List<ScheduleActivitySource> currentSources(List<Long> ids, boolean lock) {
        List<ScheduleEntity> sources = lock ? scheduleRepository.lockActivitySources(ids)
                : scheduleRepository.findAllById(ids);
        return sources.stream().sorted(java.util.Comparator.comparing(ScheduleEntity::getId))
                .map(s -> toSource(s, false)).toList();
    }

    private ScheduleEntity findSource(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
    }

    private ScheduleActivitySource toSource(ScheduleEntity source, boolean participants) {
        String type = source.isTeamScope() ? "TEAM" : source.isOrganizationScope() ? "ORGANIZATION" : "PERSONAL";
        Long scopeId = source.isTeamScope() ? source.getTeamId()
                : source.isOrganizationScope() ? source.getOrganizationId() : source.getUserId();
        List<Long> attending = participants ? attendanceRepository
                .findByScheduleIdAndStatus(source.getId(), AttendanceStatus.ATTENDING).stream()
                .map(a -> a.getUserId()).distinct().toList() : List.of();
        return new ScheduleActivitySource(source.getId(), type, scopeId, source.getTitle(), offset(source.getStartAt()),
                offset(source.getEndAt()), source.getAllDay(), source.getStatus().name(), offset(source.getUpdatedAt()), attending);
    }

    /** 既存予定のサーバ壁時計を、ゾーンが明示されたドメイン間の値へ変換する。 */
    private java.time.OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atZone(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
