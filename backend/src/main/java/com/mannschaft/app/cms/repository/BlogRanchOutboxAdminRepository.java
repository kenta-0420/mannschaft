package com.mannschaft.app.cms.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** CMS管理命令/健康集計のSQLだけを所有する。新eventや報酬を生成しない。 */
@Repository
@RequiredArgsConstructor
public class BlogRanchOutboxAdminRepository {
    private final JdbcTemplate jdbc;
    public record Health(long pending,long dead,Instant oldest) { }
    public record Command(byte[] hash,String result) { }
    public record Current(String status,Instant expires) { }
    public Health health() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(status IN ('PENDING','RETRY')),0) pending,"
                +"COALESCE(SUM(status='DEAD_LETTER'),0) dead,MIN(CASE WHEN status IN ('PENDING','RETRY') THEN created_at END) oldest "
                +"FROM blog_ranch_outboxes",(rs,index) -> {var oldest=rs.getTimestamp("oldest");return new Health(rs.getLong("pending"),rs.getLong("dead"),oldest==null?null:oldest.toInstant());});
    }
    public Command command(Long actor,UUID key) {
        // 呼出元のfresh auth row lockが同actorを直列化する。欠落行にgap lockを増やさない。
        var rows=jdbc.query("SELECT body_hash,result_json FROM blog_ranch_admin_commands WHERE actor_user_id=? AND idempotency_key=?",
                (rs,index) -> new Command(rs.getBytes(1),rs.getString(2)),actor,bytes(key));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Current current(UUID event) {
        var rows=jdbc.query("SELECT status,lease_expires_at FROM blog_ranch_outboxes WHERE id=? FOR UPDATE",
                (rs,index) -> {var expires=rs.getTimestamp(2);return new Current(rs.getString(1),expires==null?null:expires.toInstant());},bytes(event));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void requeue(UUID event,Instant now) {
        jdbc.update("UPDATE blog_ranch_outboxes SET attempt_count=CASE WHEN status='DEAD_LETTER' THEN 0 ELSE attempt_count END,"
                +"status='RETRY',next_attempt_at=?,lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL,updated_at=? WHERE id=?",
                Timestamp.from(now),Timestamp.from(now),bytes(event));
    }
    public void save(UUID command,Long actor,UUID key,byte[] hash,String result,Instant now) {
        jdbc.update("INSERT INTO blog_ranch_admin_commands(id,actor_user_id,idempotency_key,command_type,body_hash,result_json,completed_at,created_at,updated_at) "
                +"VALUES (?,?,?,'RETRY',?,?,?,?,?)",bytes(command),actor,bytes(key),hash,result,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
    }
    private static byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
}
