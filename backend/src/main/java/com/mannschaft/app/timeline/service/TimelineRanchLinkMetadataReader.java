package com.mannschaft.app.timeline.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本文を読まず、現在存在する技術IDだけを短いPRIMARY TXで確認する。 */
@Service
@RequiredArgsConstructor
class TimelineRanchLinkMetadataReader {
    private final JdbcTemplate jdbc;
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    Optional<Long> read(Long id) {
        return jdbc.queryForList("SELECT id FROM timeline_posts WHERE id=? AND deleted_at IS NULL", Long.class, id)
                .stream().findFirst();
    }
}
