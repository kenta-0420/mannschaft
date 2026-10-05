package com.mannschaft.app.timeline.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** TL短TX内のcurrent rowだけを更新する。旧tokenやpurge済み行を作り直さない。 */
@Repository
@RequiredArgsConstructor
public class TimelineRanchOutboxRepository {
    private final JdbcTemplate jdbc;

    public record Candidate(UUID eventId,String payload,int attempts,int schemaVersion,String eventType,
            long recipient,Instant occurredAt,String scopeType,String scopeIdType,byte[] scopeId) { }
    public List<Candidate> candidates(Instant now,int limit) {
        return jdbc.query("SELECT id,payload_json,attempt_count,schema_version,event_type,recipient_user_id,occurred_at,"
                +"scope_type,scope_id_type,canonical_scope_id FROM timeline_ranch_outboxes WHERE "
                +"((status IN ('PENDING','RETRY') AND next_attempt_at<=?) "
                +"OR (status='LEASED' AND lease_expires_at<=?)) ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs,index) -> new Candidate(uuid(rs.getBytes("id")),rs.getString("payload_json"),rs.getInt("attempt_count"),
                        rs.getInt("schema_version"),rs.getString("event_type"),rs.getLong("recipient_user_id"),
                        rs.getTimestamp("occurred_at").toInstant(),rs.getString("scope_type"),rs.getString("scope_id_type"),rs.getBytes("canonical_scope_id")),
                Timestamp.from(now),Timestamp.from(now),limit);
    }
    public void lease(UUID event,UUID token,Instant expires,Instant now) {
        jdbc.update("UPDATE timeline_ranch_outboxes SET status='LEASED',attempt_count=attempt_count+1,lease_token=?,"
                +"lease_expires_at=?,updated_at=? WHERE id=?",bytes(token),Timestamp.from(expires),Timestamp.from(now),bytes(event));
    }
    public void rejectCandidate(UUID event,String code,Instant now) {
        jdbc.update("UPDATE timeline_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=? WHERE id=?",code,Timestamp.from(now),bytes(event));
    }
    public Integer currentAttempt(UUID event,UUID token,Instant now) {
        var rows=jdbc.query("SELECT attempt_count FROM timeline_ranch_outboxes WHERE id=? AND status='LEASED' "
                +"AND lease_token=? AND lease_expires_at>? FOR UPDATE",(rs,index) -> rs.getInt(1),bytes(event),bytes(token),Timestamp.from(now));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean acknowledge(UUID event,UUID token,Instant now,String outcome) {
        return jdbc.update("UPDATE timeline_ranch_outboxes SET status='ACKED',terminal_outcome=?,acked_at=?,"
                +"lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=? "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>?",
                outcome,Timestamp.from(now),Timestamp.from(now),bytes(event),bytes(token),Timestamp.from(now))==1;
    }
    public boolean reschedule(UUID event,UUID token,Instant now,Instant next,String error,boolean restoreAttempt) {
        return jdbc.update("UPDATE timeline_ranch_outboxes SET status='RETRY',next_attempt_at=?,last_error_code=?,"
                +"attempt_count=attempt_count-?,lease_token=NULL,lease_expires_at=NULL,updated_at=? "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>? AND attempt_count>0",
                Timestamp.from(next),error,restoreAttempt?1:0,Timestamp.from(now),bytes(event),bytes(token),Timestamp.from(now))==1;
    }
    public boolean fail(UUID event,UUID token,Instant now,String error) {
        return jdbc.update("UPDATE timeline_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=? WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>?",
                error,Timestamp.from(now),bytes(event),bytes(token),Timestamp.from(now))==1;
    }
    private static UUID uuid(byte[] value) { var buffer=ByteBuffer.wrap(value);return new UUID(buffer.getLong(),buffer.getLong()); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
