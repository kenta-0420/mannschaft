package com.mannschaft.app.reflection.repository;

import java.nio.ByteBuffer;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** reflection管理命令/健康集計のSQLだけを所有する。新eventや報酬を生成しない。 */
@Repository
@RequiredArgsConstructor
public class ReflectionRanchOutboxAdminRepository {
    private final JdbcTemplate jdbc;
    public record Health(long pending,long dead,Long oldestAgeSeconds) { }
    public record Command(byte[] hash,String result) { }
    public record Current(String status,Instant expires,boolean activeLease) { }
    public Health health() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(status IN ('PENDING','RETRY')),0) pending,"
                +"COALESCE(SUM(status='DEAD_LETTER'),0) dead,TIMESTAMPDIFF(SECOND,MIN(CASE WHEN status IN ('PENDING','RETRY') THEN created_at END),UTC_TIMESTAMP(6)) oldest "
                +"FROM reflection_ranch_outboxes",(rs,index) -> {Long oldest=rs.getObject("oldest",Long.class);return new Health(rs.getLong("pending"),rs.getLong("dead"),oldest);});
    }
    public Command command(Long actor,UUID key) {
        // 呼出元のfresh auth row lockが同actorを直列化する。欠落行にgap lockを増やさない。
        var rows=jdbc.query("SELECT body_hash,result_json FROM reflection_ranch_admin_commands WHERE actor_user_id=? AND idempotency_key=?",
                (rs,index) -> new Command(rs.getBytes(1),rs.getString(2)),actor,bytes(key));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Current current(UUID event) {
        var rows=jdbc.query("SELECT status,lease_expires_at,(lease_expires_at>UTC_TIMESTAMP(6)) active_lease FROM reflection_ranch_outboxes WHERE id=? FOR UPDATE",
                (rs,index) -> {var expires=rs.getTimestamp(2,JdbcUtcCalendar.fresh());return new Current(rs.getString(1),expires==null?null:expires.toInstant(),rs.getBoolean("active_lease"));},bytes(event));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void requeue(UUID event,Instant now) {
        jdbc.update("UPDATE reflection_ranch_outboxes SET attempt_count=CASE WHEN status='DEAD_LETTER' THEN 0 ELSE attempt_count END,"
                +"status='RETRY',next_attempt_at=UTC_TIMESTAMP(6),lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?",bytes(event));
    }
    public void save(UUID command,Long actor,UUID key,byte[] hash,String result,Instant now) {
        jdbc.update("INSERT INTO reflection_ranch_admin_commands(id,actor_user_id,idempotency_key,command_type,body_hash,result_json,completed_at,created_at,updated_at) "
                +"VALUES (?,?,?,'OUTBOX_RETRY',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bytes(command),actor,bytes(key),hash,result);
    }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
