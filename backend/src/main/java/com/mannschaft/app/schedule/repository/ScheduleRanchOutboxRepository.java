package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 出欠短TX内のcurrent rowだけを更新する。旧tokenやpurge済み行を作り直さない。 */
@Repository
@RequiredArgsConstructor
public class ScheduleRanchOutboxRepository {
    private final JdbcTemplate jdbc;

    public record Candidate(UUID eventId,String payload,int attempts,int schemaVersion,String eventType,
            long recipient,Instant occurredAt,String scopeType,String scopeIdType,byte[] scopeId) { }
    public List<Candidate> candidates(Instant now,int limit) {
        return jdbc.query("SELECT id,payload_json,attempt_count,schema_version,event_type,recipient_user_id,occurred_at,"
                +"scope_type,scope_id_type,canonical_scope_id FROM schedule_ranch_outboxes WHERE "
                +"((status IN ('PENDING','RETRY') AND next_attempt_at<=?) "
                +"OR (status='LEASED' AND lease_expires_at<=?)) ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
                ps -> {
                    ps.setTimestamp(1,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(2,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setInt(3,limit);
                },
                (rs,index) -> new Candidate(uuid(rs.getBytes("id")),rs.getString("payload_json"),rs.getInt("attempt_count"),
                        rs.getInt("schema_version"),rs.getString("event_type"),rs.getLong("recipient_user_id"),
                        rs.getTimestamp("occurred_at",JdbcUtcCalendar.fresh()).toInstant(),rs.getString("scope_type"),rs.getString("scope_id_type"),rs.getBytes("canonical_scope_id")));
    }
    public void lease(UUID event,UUID token,Instant expires,Instant now) {
        jdbc.update("UPDATE schedule_ranch_outboxes SET status='LEASED',attempt_count=attempt_count+1,lease_token=?,"
                +"lease_expires_at=?,updated_at=? WHERE id=?",ps -> {
                    ps.setBytes(1,bytes(token));
                    ps.setTimestamp(2,Timestamp.from(expires),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(3,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setBytes(4,bytes(event));
                });
    }
    public void rejectCandidate(UUID event,String code,Instant now) {
        jdbc.update("UPDATE schedule_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=? WHERE id=?",ps -> {
                    ps.setString(1,code);
                    ps.setTimestamp(2,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setBytes(3,bytes(event));
                });
    }
    public Integer currentAttempt(UUID event,UUID token,Instant now) {
        var rows=jdbc.query("SELECT attempt_count FROM schedule_ranch_outboxes WHERE id=? AND status='LEASED' "
                +"AND lease_token=? AND lease_expires_at>? FOR UPDATE",ps -> {
                    ps.setBytes(1,bytes(event));
                    ps.setBytes(2,bytes(token));
                    ps.setTimestamp(3,Timestamp.from(now),JdbcUtcCalendar.fresh());
                },(rs,index) -> rs.getInt(1));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean acknowledge(UUID event,UUID token,Instant now,String outcome) {
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='ACKED',terminal_outcome=?,acked_at=?,"
                +"lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=? "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>?",
                ps -> {
                    ps.setString(1,outcome);
                    ps.setTimestamp(2,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setTimestamp(3,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setBytes(4,bytes(event));
                    ps.setBytes(5,bytes(token));
                    ps.setTimestamp(6,Timestamp.from(now),JdbcUtcCalendar.fresh());
                })==1;
    }
    public boolean reschedule(UUID event,UUID token,Instant now,Instant next,String error,boolean restoreAttempt) {
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='RETRY',next_attempt_at=?,last_error_code=?,"
                +"attempt_count=attempt_count-?,lease_token=NULL,lease_expires_at=NULL,updated_at=? "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>? AND attempt_count>0",
                ps -> {
                    ps.setTimestamp(1,Timestamp.from(next),JdbcUtcCalendar.fresh());
                    ps.setString(2,error);
                    ps.setInt(3,restoreAttempt?1:0);
                    ps.setTimestamp(4,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setBytes(5,bytes(event));
                    ps.setBytes(6,bytes(token));
                    ps.setTimestamp(7,Timestamp.from(now),JdbcUtcCalendar.fresh());
                })==1;
    }
    public boolean fail(UUID event,UUID token,Instant now,String error) {
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=? WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>?",
                ps -> {
                    ps.setString(1,error);
                    ps.setTimestamp(2,Timestamp.from(now),JdbcUtcCalendar.fresh());
                    ps.setBytes(3,bytes(event));
                    ps.setBytes(4,bytes(token));
                    ps.setTimestamp(5,Timestamp.from(now),JdbcUtcCalendar.fresh());
                })==1;
    }
    private static UUID uuid(byte[] value) { var buffer=ByteBuffer.wrap(value);return new UUID(buffer.getLong(),buffer.getLong()); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
