package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.dto.BlogContentFingerprint;
import com.mannschaft.app.cms.dto.BlogRanchRewardPayload;
import com.mannschaft.app.common.UuidV7;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 元本体commit後のCMS transport TXだけでwinner・witness・outboxを原子受付する。 */
@Repository
@RequiredArgsConstructor
public class BlogRanchTransportRepository {
    private final JdbcTemplate jdbc;
    public enum InsertOutcome { ACCEPTED, DUPLICATE, KEY_UNAVAILABLE }

    public InsertOutcome insertQualified(BlogRanchRewardPayload fact,BlogContentFingerprint fingerprint,String json,Instant now) {
        byte[] source=fact.canonicalSourceId().getBytes(StandardCharsets.US_ASCII);
        if(!jdbc.queryForList("SELECT id FROM blog_ranch_witnesses WHERE source_id_type='LONG' "
                +"AND canonical_source_id=? FOR UPDATE",source).isEmpty()) return InsertOutcome.DUPLICATE;
        var keyId=HexFormat.of().parseHex(fingerprint.keyId());
        var digest=HexFormat.of().parseHex(fingerprint.digest());
        var week=java.sql.Date.valueOf(fingerprint.week());
        // 旧鍵が利用できない同週履歴はUNKNOWN。同一本文の無条件新winnerにしない。
        var priorKeys=jdbc.queryForList("SELECT content_key_id FROM blog_ranch_witnesses "
                +"WHERE recipient_user_id=? AND content_week=? AND kind='QUALIFIED' FOR UPDATE",byte[].class,
                fact.recipientUserId(),week);
        if(priorKeys.stream().anyMatch(prior -> !java.security.MessageDigest.isEqual(prior,keyId))) return InsertOutcome.KEY_UNAVAILABLE;
        if(!jdbc.queryForList("SELECT id FROM blog_ranch_witnesses WHERE recipient_user_id=? "
                +"AND content_week=? AND content_version=? AND content_digest=? FOR UPDATE",
                fact.recipientUserId(),week,fingerprint.version(),digest).isEmpty()) return InsertOutcome.DUPLICATE;
        // 到着先着確定。後着のoccurredAtが早くても既存winnerを置換しない。
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO blog_ranch_witnesses (id,source_id_type,canonical_source_id,recipient_user_id,kind,"
                    +"qualifying_at,event_id,content_week,content_version,content_key_id,content_digest,created_at,updated_at) "
                    +"VALUES (?,'LONG',?,?,'QUALIFIED',?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.setBytes(1,bytes(UuidV7.generate()));statement.setBytes(2,source);statement.setLong(3,fact.recipientUserId());
            statement.setTimestamp(4,Timestamp.from(fact.occurredAt()),JdbcUtcCalendar.fresh());
            statement.setBytes(5,bytes(fact.eventId()));statement.setDate(6,week);statement.setString(7,fingerprint.version());
            statement.setBytes(8,keyId);statement.setBytes(9,digest);return statement;
        });
        byte[] canonical=("BLOG_FIRST_PUBLISH|LONG|"+fact.canonicalSourceId()).getBytes(StandardCharsets.US_ASCII);
        byte[] scope=fact.canonicalScopeId()==null?null:fact.canonicalScopeId().getBytes(StandardCharsets.US_ASCII);
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO blog_ranch_outboxes (id,schema_version,event_type,scope_type,scope_id_type,"
                    +"canonical_scope_id,recipient_user_id,canonical_key,payload_json,occurred_at,status,attempt_count,"
                    +"next_attempt_at,created_at,updated_at) VALUES (?,1,'BLOG_FIRST_PUBLISH',?,?,?,?,?,?,?,'PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.setBytes(1,bytes(fact.eventId()));statement.setString(2,fact.scopeType().name());
            statement.setString(3,fact.scopeIdType()==null?null:fact.scopeIdType().name());statement.setBytes(4,scope);
            statement.setLong(5,fact.recipientUserId());statement.setBytes(6,canonical);statement.setString(7,json);
            statement.setTimestamp(8,Timestamp.from(fact.occurredAt()),JdbcUtcCalendar.fresh());return statement;
        });
        return InsertOutcome.ACCEPTED;
    }
    public void deleteForUser(Long userId) {
        jdbc.update("DELETE FROM blog_ranch_outboxes WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM blog_ranch_witnesses WHERE recipient_user_id=?",userId);
        jdbc.update("DELETE FROM blog_ranch_admin_commands WHERE actor_user_id=?",userId);
        // 共同記事本体と単調observedを残し、消去した本人との私有資格関連だけを除く。
        jdbc.update("UPDATE blog_posts SET first_published_at=NULL,first_published_author_user_id=NULL "
                +"WHERE first_published_author_user_id=?",userId);
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
