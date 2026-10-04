package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** reflection自身の配送表だけを操作する。元の想起本文を受け取らない。 */
@Repository
@RequiredArgsConstructor
public class ReflectionRanchTransportRepository {
    private final JdbcTemplate jdbc;

    /** usersロックで同じrecipientが直列化された別reflection TX内でのみ呼ぶ。 */
    public boolean insertQualified(ReflectionRecallRewardPayload fact,String json,Instant now) {
        byte[] source=fact.canonicalSourceId().getBytes(StandardCharsets.US_ASCII);
        var prior=jdbc.queryForList("SELECT id FROM reflection_ranch_witnesses WHERE recipient_user_id=? "
                +"AND source_id_type='UUID' AND canonical_source_id=? AND reward_week=? FOR UPDATE",
                fact.recipientUserId(),source,java.sql.Date.valueOf(fact.facts().completionWeek()));
        if(!prior.isEmpty()) return false;
        jdbc.update("INSERT INTO reflection_ranch_witnesses "
                +"(id,source_id_type,canonical_source_id,recipient_user_id,kind,qualifying_at,event_id,reward_week,created_at,updated_at) "
                +"VALUES (?,'UUID',?,?,'QUALIFIED',?,?,?,?,?)",bytes(UuidV7.generate()),source,
                fact.recipientUserId(),Timestamp.from(fact.occurredAt()),bytes(fact.eventId()),
                java.sql.Date.valueOf(fact.facts().completionWeek()),Timestamp.from(now),Timestamp.from(now));
        byte[] key=("PERSONAL_RECALL_COMPLETE|UUID|"+fact.canonicalSourceId()+"|"+fact.recipientUserId()+"|"+fact.facts().completionWeek())
                .getBytes(StandardCharsets.US_ASCII);
        jdbc.update("INSERT INTO reflection_ranch_outboxes "
                +"(id,schema_version,event_type,scope_type,recipient_user_id,canonical_key,payload_json,occurred_at,"
                +"status,attempt_count,next_attempt_at,created_at,updated_at) "
                +"VALUES (?,1,'PERSONAL_RECALL_COMPLETE','PERSONAL',?,?,?,?,'PENDING',0,?,?,?)",
                bytes(fact.eventId()),fact.recipientUserId(),key,json,Timestamp.from(fact.occurredAt()),
                Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
        return true;
    }

    /** 遅延ACK/受付は削除後に行を復元しない。本人消去では同じ所有TXで全私有技術行を削除する。 */
    public void deleteForUser(Long userId) {
        jdbc.update("DELETE FROM reflection_ranch_outboxes WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM reflection_ranch_witnesses WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM reflection_ranch_admin_commands WHERE actor_user_id=?",userId);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
