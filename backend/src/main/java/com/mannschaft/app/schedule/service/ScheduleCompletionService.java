package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 完了条件・状態遷移を予定ドメイン内で扱う。活動更新は共通段取り役が同TXで行う。 */
@Service
@RequiredArgsConstructor
public class ScheduleCompletionService {
    private final ScheduleRepository schedules;
    private final ScheduleActivitySourceService sources;
    private final EntityManager entityManager;

    /** 既存予定も対象とするが、活動生成は行わない。取得量と順序はSQLで確定する。 */
    @Transactional(readOnly = true)
    public List<Long> findDueIds(OffsetDateTime now, int limit) {
        if (limit <= 0) return List.of();
        return schedules.findDueSharedConcreteIds(now.atZoneSameInstant(
                UserZoneLocalDateTimeParser.SERVER_ZONE).toLocalDateTime(),
                PageRequest.of(0, Math.min(limit, 100)));
    }

    /** 候補取得後の延期・取消・削除をcurrent readで再確認する。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScheduleActivitySource> completeIfDue(Long id, OffsetDateTime now) {
        return lockCurrent(id).filter(this::sharedConcrete)
                .filter(s -> s.getStatus() == ScheduleStatus.SCHEDULED)
                .filter(s -> s.getEndAt() != null && end(s).isBefore(now))
                .map(s -> {
                    s.complete();
                    return source(s.getId());
                });
    }

    /** 更新TXの新日時をflushしてから再読込し、refreshで変更を捨てない。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScheduleActivitySource> reopenIfFuture(Long id, OffsetDateTime now) {
        entityManager.flush();
        return lockCurrent(id).filter(this::sharedConcrete)
                .filter(s -> s.getStatus() == ScheduleStatus.COMPLETED)
                .filter(s -> s.getEndAt() != null && !end(s).isBefore(now))
                .map(s -> {
                    s.reopen();
                    return source(s.getId());
                });
    }

    private Optional<ScheduleEntity> lockCurrent(Long id) {
        List<ScheduleEntity> locked = schedules.lockActivitySources(List.of(id));
        if (locked.isEmpty()) return Optional.empty();
        ScheduleEntity current = locked.getFirst();
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(current);
    }

    private boolean sharedConcrete(ScheduleEntity value) {
        return value.getDeletedAt() == null && (value.isTeamScope() || value.isOrganizationScope())
                && !value.isRecurring();
    }

    private OffsetDateTime end(ScheduleEntity value) {
        return value.getEndAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }

    private ScheduleActivitySource source(Long id) {
        return sources.currentSources(List.of(id), false).getFirst();
    }
}
