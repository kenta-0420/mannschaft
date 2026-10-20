package com.mannschaft.app.reflection.service;

import java.nio.ByteBuffer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 私有本文をloadせず、現在エントリの存在だけを源PRIMARYで確認する。 */
@Service
@RequiredArgsConstructor
class ReflectionRanchLinkMetadataReader {
    private final JdbcTemplate jdbc;
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean exists(UUID id) {
        byte[] bytes=ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        return !jdbc.queryForList("SELECT id FROM reflection_entries WHERE id=? AND deleted_at IS NULL",bytes).isEmpty();
    }
}
