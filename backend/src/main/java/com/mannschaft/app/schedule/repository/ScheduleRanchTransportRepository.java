package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 出欠本体commit後の源TXで、初回本人証拠とoutboxだけを原子受付する。 */
@Repository
@RequiredArgsConstructor
public class ScheduleRanchTransportRepository {
    private final JdbcTemplate jdbc;
    public boolean insertQualified(ScheduleRanchRewardPayload fact,Long scheduleId,String json,Instant now) {
        byte[] source=fact.canonicalSourceId().getBytes(StandardCharsets.US_ASCII);
        if(!jdbc.queryForList("SELECT id FROM schedule_ranch_witnesses WHERE schedule_id=? "
                +"AND recipient_user_id=? FOR UPDATE",scheduleId,fact.recipientUserId()).isEmpty()) return false;
        jdbc.update("INSERT INTO schedule_ranch_witnesses (id,source_id_type,canonical_source_id,recipient_user_id,schedule_id,kind,"
                +"qualifying_at,event_id,created_at,updated_at) VALUES (?,'LONG',?,?,?,'QUALIFIED',?,?,?,?)",
                ps -> {
                    ps.setBytes(1,bytes(UuidV7.generate()));
                    ps.setBytes(2,source);
                    ps.setLong(3,fact.recipientUserId());
                    ps.setLong(4,scheduleId);
                    ps.setTimestamp(5,Timestamp.from(fact.occurredAt()),JdbcUtcCalendar.fresh());
                    ps.setBytes(6,bytes(fact.eventId()));
                    ps.setTimestamp(7,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(8,Timestamp.from(now),JdbcUtcCalendar.fresh());
                });
        byte[] canonical=("ATTENDANCE_RESPONSE|LONG|"+fact.canonicalSourceId()).getBytes(StandardCharsets.US_ASCII);
        byte[] scope=fact.canonicalScopeId()==null?null:fact.canonicalScopeId().getBytes(StandardCharsets.US_ASCII);
        jdbc.update("INSERT INTO schedule_ranch_outboxes (id,schema_version,event_type,scope_type,scope_id_type,"
                +"canonical_scope_id,recipient_user_id,canonical_key,payload_json,occurred_at,status,attempt_count,"
                +"next_attempt_at,created_at,updated_at) VALUES (?,1,'ATTENDANCE_RESPONSE',?,?,?,?,?,?,?,'PENDING',0,?,?,?)",
                ps -> {
                    ps.setBytes(1,bytes(fact.eventId()));
                    ps.setString(2,fact.scopeType().name());
                    ps.setString(3,fact.scopeIdType()==null?null:fact.scopeIdType().name());
                    ps.setBytes(4,scope);
                    ps.setLong(5,fact.recipientUserId());
                    ps.setBytes(6,canonical);
                    ps.setString(7,json);
                    ps.setTimestamp(8,Timestamp.from(fact.occurredAt()),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(9,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(10,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(11,Timestamp.from(now),JdbcUtcCalendar.fresh());
                });
        return true;
    }
    public void deleteForUser(Long userId) {
        jdbc.update("DELETE FROM schedule_ranch_outboxes WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM schedule_ranch_witnesses WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM schedule_ranch_admin_commands WHERE actor_user_id=?",userId);
        jdbc.update("UPDATE schedule_attendances SET ranch_first_self_at=NULL,ranch_first_self_user_id=NULL "
                +"WHERE ranch_first_self_user_id=?",userId);
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
