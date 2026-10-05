package com.mannschaft.app.schedule.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 回答IDを現在の予定IDへ解決する。コメント・回答内容を返さない。 */
@Service
@RequiredArgsConstructor
class ScheduleRanchLinkMetadataReader {
    private final JdbcTemplate jdbc;
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Optional<Long> read(Long responseId) {
        var rows=jdbc.queryForList("SELECT a.schedule_id FROM schedule_attendances a JOIN schedules s ON s.id=a.schedule_id "
                +"WHERE a.id=? AND s.deleted_at IS NULL",Long.class,responseId);
        return rows.stream().findFirst();
    }
}
