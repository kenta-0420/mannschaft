package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 完了・延期の予定ドメイン契約。試練先行のため実装はgreen化時に追加する。 */
@Service
public class ScheduleCompletionService {
    @Transactional(readOnly = true)
    public List<Long> findDueIds(OffsetDateTime now, int limit) { return List.of(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScheduleActivitySource> completeIfDue(Long id, OffsetDateTime now) { return Optional.empty(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ScheduleActivitySource> reopenIfFuture(Long id, OffsetDateTime now) { return Optional.empty(); }
}
