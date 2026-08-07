package com.mannschaft.app.notification.fanout.perf;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.support.perf.Fanout500kSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-001「50万人負荷試験ハーネス」四番隊 — 冪等（AC-9）／境界除外（AC-10）の実測 IT。
 *
 * <p>{@link Fanout500kSeeder} で作った ORGANIZATION 直属母集団に対して、
 * (a) 同一 sourceEventUuid の二重 enqueue が uk_fanout_idempotency で skip されること、
 * (b) FROZEN／論理削除済ユーザーは user_roles に開存があっても配信対象から除外されること、
 * を実 DB で固定する。母集団は Fanout500kSeeder の規模検証と分離するため小規模（数十件）で十分。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で静かに SKIP する。実 RUN（skipped=0）で確認すること。</p>
 */
@DisplayName("通知 fan-out 冪等/境界除外の実測IT（CMP-001・四番隊）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgIdempotencyBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgIdempotencyBoundaryIT.class);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;

    // =====================================================================
    // AC-9: 同一 sourceEventUuid の二重 enqueue は uk_fanout_idempotency で skip される
    // =====================================================================
    @Test
    @DisplayName("AC-9 同一(scope,type,sourceEventUuid)の二重 enqueue はジョブ1件・二回目は静かに skip")
    void ac9_duplicateEnqueueSkippedByUniqueConstraint() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(20);
        String type = "FANOUT_500K_AC9";
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-9 冪等", "本文",
                NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null);
        // 二回目（同一キー）は uk_fanout_idempotency 衝突で静かに skip される想定。
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-9 冪等", "本文",
                NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type = ? AND scope_ref = ? "
                        + "AND notification_type = ?",
                Long.class, OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type);
        Optional<NotificationFanoutJob> found = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        log.info("[AC-9] rows={} present={}", rows, found.isPresent());
        perf("AC9_rows=" + rows + " AC9_present=" + found.isPresent());
        assertThat(found).as("AC-9: 冪等キーのジョブが実在する").isPresent();
        assertThat(rows).as("AC-9: 二重 enqueue でもジョブ行はちょうど1件（uk_fanout_idempotency）").isEqualTo(1L);
    }

    // =====================================================================
    // AC-10: FROZEN／論理削除済ユーザーは user_roles 開存でも配信対象から除外される
    // =====================================================================
    @Test
    @DisplayName("AC-10 FROZEN/論理削除済ユーザーは user_roles 開存でも fan-out 配信対象から除外される")
    void ac10_nonActiveOrDeletedUsersExcludedFromDelivery() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(10); // 10 名 ACTIVE を母集団として先に投入

        // FROZEN ユーザーと論理削除済ユーザーを追加投入する（同一組織へ user_roles 開存させる）。
        long frozenUserId = seed.userIdTo() + 1;
        long deletedUserId = seed.userIdTo() + 2;
        insertBoundaryUser(frozenUserId, "FROZEN", null);
        insertBoundaryUser(deletedUserId, "ACTIVE", LocalDateTime.now().minusHours(1));
        insertUserRole(frozenUserId, seed.organizationId());
        insertUserRole(deletedUserId, seed.organizationId());

        String type = "FANOUT_500K_AC10";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-10 境界除外", "本文",
                NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null);
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent)
                .orElseThrow();

        worker.processOne(job);

        long delivered = countNotifications(type);
        long deliveredToFrozen = countNotificationsForUser(type, frozenUserId);
        long deliveredToDeleted = countNotificationsForUser(type, deletedUserId);

        log.info("[AC-10] delivered={} frozen={} deleted={}（母集団={}）",
                delivered, deliveredToFrozen, deliveredToDeleted, seed.memberCount());
        perf("AC10_delivered=" + delivered + " AC10_frozen=" + deliveredToFrozen
                + " AC10_deleted=" + deliveredToDeleted + " AC10_population=" + seed.memberCount());

        assertThat(delivered).as("AC-10: ACTIVE 母集団のみに配信（FROZEN/削除は含まない）")
                .isEqualTo(seed.memberCount());
        assertThat(deliveredToFrozen).as("AC-10: FROZEN ユーザーは user_roles 開存でも非受信").isZero();
        assertThat(deliveredToDeleted).as("AC-10: 論理削除済ユーザーは user_roles 開存でも非受信").isZero();
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }

    private void insertBoundaryUser(long userId, String status, LocalDateTime deletedAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users ("
                        + "id, email, last_name, first_name, display_name, status, deleted_at, created_at, updated_at, "
                        + "handle_searchable, contact_approval_required, online_visibility, is_searchable, dm_receive_from, "
                        + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only"
                        + ") VALUES ("
                        + "?, ?, 'L', 'F', ?, ?, ?, ?, ?, "
                        + "1, 1, 'NOBODY', 1, 'ANYONE', "
                        + "1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0)",
                userId, "fanout500k-boundary-" + userId + "@example.test", "U" + userId, status, deletedAt, now, now);
    }

    private void insertUserRole(long userId, long organizationId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "VALUES (?, 3, NULL, ?, ?, ?)", userId, organizationId, now, now);
    }

    private long countNotifications(String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ?", Long.class, type);
        return c == null ? 0L : c;
    }

    private long countNotificationsForUser(String type, long userId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id = ?",
                Long.class, type, userId);
        return c == null ? 0L : c;
    }
}
