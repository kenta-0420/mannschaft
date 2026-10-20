package com.mannschaft.app.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本文を読まず、対応済みPERSONAL経路かだけを独立PRIMARY読取する。 */
@Service
@RequiredArgsConstructor
class ScheduleRanchNativeReader {
    private final JdbcTemplate jdbc;
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean personal(Long schedule,Long actor) {
        return !jdbc.queryForList("SELECT id FROM schedules WHERE id=? AND user_id=? AND team_id IS NULL "
                +"AND organization_id IS NULL AND deleted_at IS NULL",schedule,actor).isEmpty();
    }
}
