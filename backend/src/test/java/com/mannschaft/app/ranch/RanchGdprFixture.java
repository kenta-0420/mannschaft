package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 隔離MySQLだけに本人所有の全11表代表行を用意するGDPR試験fixture。 */
final class RanchGdprFixture {
    private static final AtomicLong POLICY_WEEK = new AtomicLong();
    private RanchGdprFixture() { }

    static FixtureIds populate(JdbcTemplate jdbc, Long userId, UUID ownerId, UUID dinosaurId) {
        String owner = ownerId.toString();
        String dinosaur = dinosaurId.toString();
        jdbc.update("""
                INSERT INTO ranch_care_week_budgets
                (id, created_at, updated_at, owner_id, user_id, week_starts_on, rule_id,
                 rule_snapshot, weekly_cap_xp, awarded_xp, version)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?,
                        DATE_SUB(UTC_DATE(), INTERVAL WEEKDAY(UTC_DATE()) DAY), UUID_TO_BIN(?),
                        JSON_OBJECT('fixture', TRUE), 100, 20, 1)
                """, UuidV7.generate().toString(), owner, userId, UuidV7.generate().toString());
        jdbc.update("""
                INSERT INTO ranch_affinity_units
                (id, created_at, updated_at, owner_id, user_id, dinosaur_id, earned_on, kind, gain)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?,
                        UUID_TO_BIN(?), UTC_DATE(), 'FEED', 1)
                """, UuidV7.generate().toString(), owner, userId, dinosaur);
        jdbc.update("""
                INSERT INTO ranch_point_ledger
                (id, created_at, updated_at, owner_id, user_id, decision_id, command_id,
                 entry_kind, delta_points, balance_after, delta_xp, dinosaur_id, rule_snapshot, occurred_at)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?,
                        NULL, UUID_TO_BIN(?), 'CARE', 0, 0, 20, UUID_TO_BIN(?),
                        JSON_OBJECT('fixture', TRUE), UTC_TIMESTAMP(6))
                """, UuidV7.generate().toString(), owner, userId, UuidV7.generate().toString(), dinosaur);

        String collectible = "GDPR_FIXTURE_" + userId + "_" + UuidV7.generate();
        jdbc.update("""
                INSERT INTO ranch_collectible_catalog
                (collectible_key, label_key, asset_key, source_kind, is_active, created_at, updated_at)
                VALUES (?, 'gdpr-fixture', 'gdpr-fixture', 'LEGACY_BADGE', FALSE,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, collectible);
        jdbc.update("""
                INSERT INTO ranch_collectible_inventory
                (id, created_at, updated_at, owner_id, user_id, collectible_key, acquisition_kind,
                 acquisition_key, legacy_badge_id, legacy_award_period, sku_key, price_version,
                 awarded_at, is_revoked)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?, ?,
                        'LEGACY_BADGE', ?, ?, '2026-W40', NULL, NULL, UTC_TIMESTAMP(6), FALSE)
                """, UuidV7.generate().toString(), owner, userId, collectible,
                ("GDPR:" + userId).getBytes(StandardCharsets.US_ASCII), "GDPR_BADGE_" + userId);

        UUID policyId = UuidV7.generate();
        long sequence = POLICY_WEEK.getAndIncrement();
        long version = 1_000_000_000L + sequence;
        Instant effectiveAt = Instant.parse("2020-01-06T00:00:00Z")
                .plus(sequence * 7, ChronoUnit.DAYS);
        EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule> sourceRules =
                new EnumMap<>(RanchRewardSourceType.class);
        for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
            sourceRules.put(type, new RanchRewardPolicySnapshot.SourceRule(false, 1, 1));
        }
        var policy = new RanchRewardPolicySnapshot(policyId, version, effectiveAt,
                false, 100, sourceRules,
                new RanchRewardPolicySnapshot.DeliverySettings(1, 1, 1, 1, 1),
                "GDPR_FIXTURE");
        var encoded = RanchRewardPolicyCodec.encode(policy, new ObjectMapper());
        jdbc.update("""
                INSERT INTO ranch_reward_policies
                (id, created_at, updated_at, version_number, effective_at, schema_version,
                 settings_json, content_hash, published_by, published_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """, policyId.toString(), Timestamp.from(effectiveAt), Timestamp.from(effectiveAt),
                version, Timestamp.from(effectiveAt), encoded.json(), encoded.sha256(),
                userId, Timestamp.from(effectiveAt));
        jdbc.update("""
                INSERT INTO ranch_week_budgets
                (id, created_at, updated_at, owner_id, user_id, week_starts_on, policy_id,
                 rule_snapshot, global_cap, awarded_total, source_counts, version)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?,
                        DATE_SUB(UTC_DATE(), INTERVAL WEEKDAY(UTC_DATE()) DAY), UUID_TO_BIN(?),
                        JSON_OBJECT('fixture', TRUE), 100, 0, JSON_OBJECT(), 0)
                """, UuidV7.generate().toString(), owner, userId, policyId.toString());
        String canonical = "GDPR:SOURCE:" + userId;
        jdbc.update("""
                INSERT INTO ranch_reward_decisions
                (id, created_at, updated_at, owner_id, user_id, event_id, source_type,
                 canonical_key_hash, canonical_key, reward_week, policy_id, status,
                 requested_points, awarded_points, occurred_at, decided_at)
                VALUES (UUID_TO_BIN(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UUID_TO_BIN(?), ?,
                        UUID_TO_BIN(?), 'ATTENDANCE_RESPONSE', UNHEX(SHA2(?, 256)), ?,
                        DATE_SUB(UTC_DATE(), INTERVAL WEEKDAY(UTC_DATE()) DAY), NULL,
                        'SOURCE_DISABLED', 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidV7.generate().toString(), owner, userId, UuidV7.generate().toString(),
                canonical, canonical.getBytes(StandardCharsets.US_ASCII));
        return new FixtureIds(policyId, collectible);
    }

    record FixtureIds(UUID policyId, String collectibleKey) { }
}
