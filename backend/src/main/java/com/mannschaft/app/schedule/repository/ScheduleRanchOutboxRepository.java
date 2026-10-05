package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryDelay;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
                +"((status IN ('PENDING','RETRY') AND next_attempt_at<=UTC_TIMESTAMP(6)) "
                +"OR (status='LEASED' AND lease_expires_at<=UTC_TIMESTAMP(6))) ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED",
                ps -> {
                    ps.setInt(1,limit);
                },
                (rs,index) -> new Candidate(uuid(rs.getBytes("id")),rs.getString("payload_json"),rs.getInt("attempt_count"),
                        rs.getInt("schema_version"),rs.getString("event_type"),rs.getLong("recipient_user_id"),
                        rs.getTimestamp("occurred_at",JdbcUtcCalendar.fresh()).toInstant(),rs.getString("scope_type"),rs.getString("scope_id_type"),rs.getBytes("canonical_scope_id")));
    }
    public Optional<Instant> lease(UUID event,UUID token,Instant expires,Instant now) {
        int seconds=SourceOutboxDeliveryDelay.positiveCeilingSeconds(now,expires);
        int changed=jdbc.update("UPDATE schedule_ranch_outboxes SET status='LEASED',attempt_count=attempt_count+1,lease_token=?,"
                +"lease_expires_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE id=? "
                +"AND ((status IN ('PENDING','RETRY') AND next_attempt_at<=UTC_TIMESTAMP(6)) "
                +"OR (status='LEASED' AND lease_expires_at<=UTC_TIMESTAMP(6)))",ps -> {
                    ps.setBytes(1,bytes(token));
                    ps.setInt(2,seconds);
                    ps.setBytes(3,bytes(event));
                });
        if(changed!=1) return Optional.empty();
        // 呼出元の源TXに参加する同じJdbcTemplateで、DBが保存したこのtokenの期限を返す。
        var rows=jdbc.query("SELECT lease_expires_at FROM schedule_ranch_outboxes WHERE id=? "
                +"AND status='LEASED' AND lease_token=?",(rs,index) -> {
                    Timestamp stored=rs.getTimestamp(1,JdbcUtcCalendar.fresh());
                    return stored==null?null:stored.toInstant();
                },bytes(event),bytes(token));
        return rows.isEmpty()?Optional.empty():Optional.ofNullable(rows.getFirst());
    }
    public void rejectCandidate(UUID event,String code,Instant now) {
        jdbc.update("UPDATE schedule_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?",ps -> {
                    ps.setString(1,code);
                    ps.setBytes(2,bytes(event));
                });
    }
    public Integer currentAttempt(UUID event,UUID token,Instant now) {
        var rows=jdbc.query("SELECT attempt_count FROM schedule_ranch_outboxes WHERE id=? AND status='LEASED' "
                +"AND lease_token=? AND lease_expires_at>UTC_TIMESTAMP(6) FOR UPDATE",ps -> {
                    ps.setBytes(1,bytes(event));
                    ps.setBytes(2,bytes(token));
                },(rs,index) -> rs.getInt(1));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean acknowledge(UUID event,UUID token,Instant now,String outcome) {
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='ACKED',terminal_outcome=?,acked_at=UTC_TIMESTAMP(6),"
                +"lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=UTC_TIMESTAMP(6) "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>UTC_TIMESTAMP(6)",
                ps -> {
                    ps.setString(1,outcome);
                    ps.setBytes(2,bytes(event));
                    ps.setBytes(3,bytes(token));
                })==1;
    }
    public boolean reschedule(UUID event,UUID token,Instant now,Instant next,String error,boolean restoreAttempt) {
        int seconds=SourceOutboxDeliveryDelay.positiveCeilingSeconds(now,next);
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='RETRY',next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),last_error_code=?,"
                +"attempt_count=attempt_count-?,lease_token=NULL,lease_expires_at=NULL,updated_at=UTC_TIMESTAMP(6) "
                +"WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>UTC_TIMESTAMP(6) AND attempt_count>0",
                ps -> {
                    ps.setInt(1,seconds);
                    ps.setString(2,error);
                    ps.setInt(3,restoreAttempt?1:0);
                    ps.setBytes(4,bytes(event));
                    ps.setBytes(5,bytes(token));
                })==1;
    }
    public boolean fail(UUID event,UUID token,Instant now,String error) {
        return jdbc.update("UPDATE schedule_ranch_outboxes SET status='DEAD_LETTER',last_error_code=?,lease_token=NULL,"
                +"lease_expires_at=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='LEASED' AND lease_token=? AND lease_expires_at>UTC_TIMESTAMP(6)",
                ps -> {
                    ps.setString(1,error);
                    ps.setBytes(2,bytes(event));
                    ps.setBytes(3,bytes(token));
                })==1;
    }
    private static UUID uuid(byte[] value) { var buffer=ByteBuffer.wrap(value);return new UUID(buffer.getLong(),buffer.getLong()); }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
