package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.outbox.NotificationOutboxRelay;
import com.mannschaft.app.team.service.TeamAffiliationNotice;
import com.mannschaft.app.team.service.TeamAffiliationNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 通知 outbox（docs/architecture/notification_outbox.md）の試練 IT が共有する補助。
 *
 * <p>テストメソッドに tx を張らず、フィクスチャはコミットして {@link #cleanUpOutbox()} で物理削除する
 * （outbox の claim・取り込み・印付けはそれぞれ独立した tx で、コミットを伴わないと検証できない）。
 * 試験プロファイルでは起こし（AFTER_COMMIT の即時 drain）を止めてあり（{@code mannschaft.notification.outbox.nudge-enabled=false}）、
 * 予備ポーラーも動かないので、取り込みは {@link #drain()} を同期で呼んだときだけ起きる。</p>
 *
 * <p>outbox の行は、実物の書き込み口 {@link TeamAffiliationNotifier#enqueue}（業務と同じ tx で outbox に1行書く）を
 * tx の中で呼んで作る（{@link #appendNotice}）。membershipId は架空でよい（取り込みは加盟行を読まない）。</p>
 */
abstract class TeamNotificationOutboxItSupport extends TeamAffiliationItSupport {

    /** 架空の membershipId の帯（他の IT の AUTO_INCREMENT と重ならない）。 */
    private static final java.util.concurrent.atomic.AtomicLong FAKE_MEMBERSHIP_SEQ =
            new java.util.concurrent.atomic.AtomicLong(970_000_000L);

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TeamAffiliationNotifier notifier;

    @Autowired
    protected NotificationOutboxRelay relay;

    @Autowired
    protected MeterRegistry meterRegistry;

    @AfterEach
    void cleanUpOutbox() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // 書き込み・取り込み
    // =====================================================================

    /** フィクスチャ作りなどを tx に包んでコミットする。 */
    protected <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    protected long nextFakeMembershipId() {
        return FAKE_MEMBERSHIP_SEQ.incrementAndGet();
    }

    /**
     * 加盟申請の通知（{@code TEAM_ORG_APPLICATION_RECEIVED}・受信者は組織 ADMIN）を、tx の中で書き込み口に渡してコミットする。
     *
     * @return 使った membershipId（冪等キーの元）
     */
    protected long appendNotice(OrgFx org, long membershipId) {
        inTx(() -> {
            notifier.enqueue(TeamAffiliationNotice.applicationReceived(
                    org.id(), org.slug(), "outbox試験チーム", org.name(), membershipId, null));
            return null;
        });
        return membershipId;
    }

    protected long appendNotice(OrgFx org) {
        return appendNotice(org, nextFakeMembershipId());
    }

    /** relay を同期で1回 drain する（全 source を空になるまで回す）。 */
    protected int drain() {
        return relay.drainAll();
    }

    // =====================================================================
    // 観測
    // =====================================================================

    /** 冪等キー（F01.2.1 §6.7: {@code F01.2.1:<type>:<membershipId>}）。 */
    protected static UUID idempotencyKeyOf(NotificationType type, long membershipId) {
        return UUID.nameUUIDFromBytes(("F01.2.1:" + type.name() + ":" + membershipId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 冪等キーで outbox の行を引く（無ければ null）。 */
    protected Map<String, Object> outboxRow(UUID idempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, idempotency_key, message_kind, payload_version, notification_type, organization_id, "
                        + "status, attempt_count, next_attempt_at, claim_token, claimed_at, relayed_at, dead_at, "
                        + "last_error, created_at FROM team_notification_outbox WHERE idempotency_key = ?",
                uuidBytes(idempotencyKey));
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected Map<String, Object> outboxRowOf(long membershipId) {
        return outboxRow(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId));
    }

    protected long outboxCount(long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_notification_outbox WHERE organization_id = ?", Long.class, orgId);
        return count == null ? 0 : count;
    }

    /** 冪等キー（= {@code source_event_uuid}）で引いた fan-out ジョブの行数。 */
    protected long jobCount(UUID idempotencyKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_event_uuid = ?",
                Long.class, uuidBytes(idempotencyKey));
        return count == null ? 0 : count;
    }

    protected long jobCountOf(long membershipId) {
        return jobCount(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId));
    }

    protected long jobCountByOrganization(long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE organization_id = ?", Long.class, orgId);
        return count == null ? 0 : count;
    }

    /** 冪等キーで引いたジョブの文面行の数。 */
    protected long jobMessageCount(UUID idempotencyKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_job_messages WHERE job_id IN "
                        + "(SELECT id FROM notification_fanout_jobs WHERE source_event_uuid = ?)",
                Long.class, uuidBytes(idempotencyKey));
        return count == null ? 0 : count;
    }

    protected String statusOf(long membershipId) {
        Map<String, Object> row = outboxRowOf(membershipId);
        return row == null ? null : (String) row.get("status");
    }

    protected int attemptOf(long membershipId) {
        return ((Number) outboxRowOf(membershipId).get("attempt_count")).intValue();
    }

    /** 再試行待ちの行をすぐ claim できるようにする（{@code next_attempt_at} を過去へ）。 */
    protected void makeDueNow(long membershipId) {
        jdbc.update("UPDATE team_notification_outbox SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                        + "WHERE idempotency_key = ?",
                uuidBytes(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId)));
    }

    /** DATETIME（UTC で保存）を Instant にする。 */
    protected static Instant instantOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        throw new IllegalArgumentException("DATETIME の型が想定外: " + value.getClass());
    }

    /** DB の現在時刻（UTC）。 */
    protected Instant dbNowUtc() {
        return instantOf(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Object.class));
    }

    /** カウンタの合計（tag をまたいで足す。未登録なら0）。 */
    protected double counterSum(String name) {
        return meterRegistry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    protected static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    protected static UUID uuidFromBytes(Object raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap((byte[]) raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
