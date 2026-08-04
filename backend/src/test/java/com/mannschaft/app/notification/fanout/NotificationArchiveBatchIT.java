package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.event.NotificationAnonymizationEventListener;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationCleanupBatchService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保持バッチ本体（アーカイブ移送）Wave2-A の受け入れ IT。
 *
 * <p>{@link NotificationCleanupBatchService} が保持期間超過（既読90日/未読365日）の通知を
 * {@code notifications_archive} へ移送し本体から削除すること、および退会即時消去層が
 * archive 側の PII も消すことを検証する。</p>
 *
 * <p><b>1次キャッシュ罠回避</b>: 検証は {@code findById} でなく
 * 実 DB 状態（{@link JdbcTemplate} の COUNT / 実クエリ）で行う。
 * seed 後は {@link EntityManager#clear()} し、{@code created_at} は
 * {@code @PrePersist} が now() で上書きするため保存後に JDBC で明示上書きする（TZ 境界は LocalDateTime bind）。</p>
 */
@DisplayName("Wave2-A 保持バッチ本体（アーカイブ移送）IT")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
// test プロファイルは ddl-auto=create で schema を JPA Entity 由来に生成し Flyway を適用しない。
// notifications_archive は archive 用 @Entity を持たない（D-2b UUIDv7 規約回避）ため Entity 由来で生成されない。
// DDL の二重管理・ドリフトを避けるため、実在の V173 移送 SQL をそのまま @Sql で適用して表＋索引を用意する。
@Sql(scripts = "classpath:db/migration/V173.20260730033807__create_notifications_archive_and_read_index.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class NotificationArchiveBatchIT extends AbstractMySqlIntegrationTest {

    private static final int READ_RETENTION_DAYS = 90;
    private static final int UNREAD_RETENTION_DAYS = 365;

    @Autowired
    private NotificationCleanupBatchService cleanupBatchService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationAnonymizationEventListener anonymizationListener;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM notifications_archive");
        jdbc.update("DELETE FROM notifications");
    }

    // ============================== seed ヘルパ ==============================

    /** 全列を充填した通知を保存し、created_at を daysAgo 日前に明示上書きして id を返す。 */
    private Long seedNotification(Long userId, boolean isRead, int daysAgo) {
        NotificationEntity n = NotificationEntity.builder()
                .userId(userId)
                .organizationId(777L)
                .notificationType("VILLAGE_EVENT")
                .priority(NotificationPriority.HIGH)
                .title("タイトル-" + userId)
                .body("本文-" + userId)
                .sourceType("VILLAGE_EVENT")
                .sourceId(4242L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(555L)
                .actionUrl("/villages/1/events/2")
                .actorId(999L)
                .isRead(isRead)
                .readAt(isRead ? LocalDateTime.now().minusDays(daysAgo) : null)
                .channelsSent("[\"PUSH\"]")
                .snoozedUntil(null)
                .build();
        NotificationEntity saved = notificationRepository.saveAndFlush(n);
        Long id = saved.getId();
        // @PrePersist が created_at を now() にするため、年齢を JDBC で確定的に上書きする。
        jdbc.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(daysAgo), id);
        entityManager.clear();
        return id;
    }

    private long notificationCount(Long id) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id = ?", Long.class, id);
        return c == null ? 0 : c;
    }

    private long archiveCount(Long id) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM notifications_archive WHERE id = ?", Long.class, id);
        return c == null ? 0 : c;
    }

    // ============================== AC-1 ==============================

    @Test
    @DisplayName("AC-1 既読90日超が archive へ INSERT され本体から DELETE される（物理削除→退避）")
    void ac1_readOverRetentionMovedToArchive() {
        Long id = seedNotification(101L, true, READ_RETENTION_DAYS + 1);

        cleanupBatchService.cleanupOldReadNotifications();

        assertThat(archiveCount(id)).as("archive へ移送されている").isEqualTo(1);
        assertThat(notificationCount(id)).as("本体からは削除されている").isZero();
    }

    // ============================== AC-2 ==============================

    @Test
    @DisplayName("AC-2 移送後 archive に全カラム値が保持され id は採番されず引き継がれる")
    void ac2_allColumnsPreservedAndIdCarriedOver() {
        Long id = seedNotification(102L, true, READ_RETENTION_DAYS + 5);

        cleanupBatchService.cleanupOldReadNotifications();

        // archive 用 @Entity は持たない（D-2b UUIDv7 規約回避のため JdbcTemplate 直運用）。
        // 移送行の列保持は実 DB の行を Map で取得して検証する。
        assertThat(archiveCount(id)).as("archive に移送行が1本ある").isEqualTo(1);
        java.util.Map<String, Object> a =
                jdbc.queryForMap("SELECT * FROM notifications_archive WHERE id = ?", id);
        assertThat(((Number) a.get("id")).longValue())
                .as("id は元 notifications.id を引き継ぐ（採番しない）").isEqualTo(id);
        assertThat(((Number) a.get("user_id")).longValue()).isEqualTo(102L);
        assertThat(((Number) a.get("organization_id")).longValue()).isEqualTo(777L);
        assertThat(a.get("notification_type")).isEqualTo("VILLAGE_EVENT");
        assertThat(a.get("priority")).isEqualTo("HIGH");
        assertThat(a.get("title")).isEqualTo("タイトル-102");
        assertThat(a.get("body")).isEqualTo("本文-102");
        assertThat(a.get("source_type")).isEqualTo("VILLAGE_EVENT");
        assertThat(((Number) a.get("source_id")).longValue()).isEqualTo(4242L);
        assertThat(a.get("scope_type")).isEqualTo(NotificationScopeType.TEAM.name());
        assertThat(((Number) a.get("scope_id")).longValue()).isEqualTo(555L);
        assertThat(a.get("action_url")).isEqualTo("/villages/1/events/2");
        assertThat(((Number) a.get("actor_id")).longValue()).isEqualTo(999L);
        Object isRead = a.get("is_read");
        boolean isReadValue = isRead instanceof Number
                ? ((Number) isRead).intValue() != 0 : Boolean.TRUE.equals(isRead);
        assertThat(isReadValue).as("既読フラグが保持される").isTrue();
        assertThat(a.get("read_at")).as("既読日時が保持される").isNotNull();
        assertThat(a.get("channels_sent").toString()).contains("PUSH");
        assertThat(a.get("created_at")).as("作成日時が保持される").isNotNull();
        assertThat(a.get("archived_at")).as("移送日時が刻まれる").isNotNull();
    }

    // ============================== AC-3 ==============================

    @Test
    @DisplayName("AC-3 archive に入った id のみ DELETE（移送されない行は本体に残す安全弁）")
    void ac3_onlyArchivedIdsDeleted() {
        Long oldRead = seedNotification(103L, true, READ_RETENTION_DAYS + 10);   // 移送対象
        Long freshRead = seedNotification(103L, true, READ_RETENTION_DAYS - 10); // 対象外（新しい既読）
        Long freshUnread = seedNotification(103L, false, 3);                      // 対象外（新しい未読）

        cleanupBatchService.cleanupOldReadNotifications();

        // 本体から消えた行は必ず archive に存在する（archive 不在のまま DELETE されない）。
        assertThat(notificationCount(oldRead)).as("移送対象は本体から消える").isZero();
        assertThat(archiveCount(oldRead)).as("消えた行は archive に居る").isEqualTo(1);

        assertThat(notificationCount(freshRead)).as("対象外の新しい既読は残る").isEqualTo(1);
        assertThat(archiveCount(freshRead)).isZero();
        assertThat(notificationCount(freshUnread)).as("対象外の新しい未読は残る").isEqualTo(1);
        assertThat(archiveCount(freshUnread)).isZero();
    }

    // ============================== AC-4 ==============================

    @Test
    @DisplayName("AC-4 冪等: 再移送しても重複エラーにならない（INSERT IGNORE 相当・archive は1行のまま）")
    void ac4_idempotentReRun() {
        Long id = seedNotification(104L, true, READ_RETENTION_DAYS + 2);

        cleanupBatchService.cleanupOldReadNotifications();
        // 本体から消えた後にもう一度同じ id を archive へ入れても衝突しないことを確かめるため、
        // 再度同一 id・同一鍵の行を本体へ復元して二度目の移送を走らせる。
        jdbc.update("INSERT INTO notifications " +
                        "(id, user_id, organization_id, notification_type, priority, title, body, " +
                        " source_type, source_id, scope_type, scope_id, action_url, actor_id, " +
                        " is_read, read_at, channels_sent, snoozed_until, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 104L, 777L, "VILLAGE_EVENT", "HIGH", "タイトル-104", "本文-104",
                "VILLAGE_EVENT", 4242L, "TEAM", 555L, "/x", 999L,
                true, LocalDateTime.now().minusDays(READ_RETENTION_DAYS + 2), "[\"PUSH\"]", null,
                LocalDateTime.now().minusDays(READ_RETENTION_DAYS + 2));

        cleanupBatchService.cleanupOldReadNotifications();

        assertThat(archiveCount(id)).as("冪等: 再移送でも archive は id あたり1行").isEqualTo(1);
        assertThat(notificationCount(id)).isZero();
    }

    // ============================== AC-5 ==============================

    @Test
    @DisplayName("AC-5 未読365日超も archive へ退避され本体から削除される（未読の青天井封鎖）")
    void ac5_unreadOverAgingRetentionArchived() {
        Long id = seedNotification(105L, false, UNREAD_RETENTION_DAYS + 1);

        cleanupBatchService.cleanupOldReadNotifications();

        assertThat(archiveCount(id)).as("超高齢未読も退避される").isEqualTo(1);
        assertThat(notificationCount(id)).as("本体から削除される").isZero();
    }

    // ============================== AC-6 ==============================

    @Test
    @DisplayName("AC-6 未読エイジング閾値ちょうど/±1日の境界判定")
    void ac6_unreadAgingBoundary() {
        Long younger = seedNotification(106L, false, UNREAD_RETENTION_DAYS - 1); // 対象外
        Long boundary = seedNotification(106L, false, UNREAD_RETENTION_DAYS);    // 閾値ちょうど（走行時に閾値未満へ）→対象
        Long older = seedNotification(106L, false, UNREAD_RETENTION_DAYS + 1);   // 対象

        cleanupBatchService.cleanupOldReadNotifications();

        assertThat(notificationCount(younger)).as("364日相当は残る").isEqualTo(1);
        assertThat(archiveCount(younger)).isZero();

        assertThat(archiveCount(boundary)).as("365日ちょうどは退避される").isEqualTo(1);
        assertThat(notificationCount(boundary)).isZero();

        assertThat(archiveCount(older)).as("366日は退避される").isEqualTo(1);
        assertThat(notificationCount(older)).isZero();
    }

    // ============================== AC-7 ==============================

    @Test
    @DisplayName("AC-7 UserAnonymizedEvent で notifications_archive の当該 user_id 行が削除される（PII残留0）")
    void ac7_anonymizationPurgesArchiveRows() {
        long victim = 107L;
        long bystander = 108L;
        // archive に victim / bystander の PII を直接 seed（移送済み状態を模す）。
        insertArchiveRow(9_001L, victim);
        insertArchiveRow(9_002L, victim);
        insertArchiveRow(9_003L, bystander);
        entityManager.clear();

        anonymizationListener.handleUserAnonymized(new UserAnonymizedEvent(victim, "old@example.com"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long remaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications_archive WHERE user_id = ?", Long.class, victim);
            assertThat(remaining).as("退会ユーザーの archive PII は残留0").isZero();
        });
        Long bystanderRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications_archive WHERE user_id = ?", Long.class, bystander);
        assertThat(bystanderRows).as("無関係ユーザーの archive 行は保持される").isEqualTo(1);
    }

    // ============================== AC-8 ==============================

    @Test
    @DisplayName("AC-8 archive 削除は即時消去層（UserAnonymizedEvent リスナー）で起きる（30日 AccountPurge 側でない）")
    void ac8_archiveDeletionHappensInImmediateLayer() {
        long victim = 109L;
        insertArchiveRow(9_010L, victim);
        entityManager.clear();

        // 即時消去層 = UserAnonymizedEvent を受ける本リスナー。ここで消えることを検証する
        // （30日後の AccountPurge を待たずに archive PII が消える＝即時層の責務）。
        anonymizationListener.handleUserAnonymized(new UserAnonymizedEvent(victim, "old@example.com"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long remaining = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications_archive WHERE user_id = ?", Long.class, victim);
            assertThat(remaining).as("即時消去層で archive PII が消える").isZero();
        });
    }

    // ============================== AC-9 ==============================

    @Test
    @DisplayName("AC-9 移送整合: INSERT成功後DELETE・存在確認付きで欠落なし・重複なし（クラッシュ再開耐性の代理検証）")
    void ac9_moveConsistencyNoLossNoDuplicate() {
        // 混在集合を投入し、移送後に「本体 XOR archive にちょうど1回だけ存在」を全 id で確認する。
        // 消えたのに archive に無い（＝欠落）や、本体と archive の両方に居る（＝重複）が
        // 1件も無いことが at-least-once 移送（存在確認 DELETE）の不変条件。
        Long a = seedNotification(110L, true, READ_RETENTION_DAYS + 3);       // 移送対象
        Long b = seedNotification(110L, false, UNREAD_RETENTION_DAYS + 3);    // 移送対象
        Long c = seedNotification(110L, true, READ_RETENTION_DAYS - 5);       // 対象外

        cleanupBatchService.cleanupOldReadNotifications();

        for (Long id : new Long[]{a, b, c}) {
            long inMain = notificationCount(id);
            long inArchive = archiveCount(id);
            assertThat(inMain + inArchive)
                    .as("id=%s は本体 XOR archive にちょうど1回存在（欠落も重複もなし）", id)
                    .isEqualTo(1);
        }
        assertThat(notificationCount(a)).isZero();
        assertThat(archiveCount(a)).isEqualTo(1);
        assertThat(notificationCount(b)).isZero();
        assertThat(archiveCount(b)).isEqualTo(1);
        assertThat(notificationCount(c)).isEqualTo(1);
        assertThat(archiveCount(c)).isZero();
    }

    // ============================== AC-9 クラッシュ再開 ==============================

    @Test
    @DisplayName("AC-9(再開) 前回INSERTだけコミット済の孤児行を、再走で本体からDELETEし二重在庫を解消する")
    void ac9_crashRestartPurgesOrphanFromMainTable() {
        // 移送対象行を本体に作り、かつ同一 id を archive にも直接 INSERT して
        // 「前回チャンクで INSERT だけコミットされ DELETE 前にクラッシュした」状態を再現する。
        Long orphan = seedNotification(111L, true, READ_RETENTION_DAYS + 7);
        // archive 側に同一 id の行を先行投入（移送済みだが本体に残っている＝孤児）。
        jdbc.update("INSERT INTO notifications_archive " +
                        "(id, user_id, organization_id, notification_type, priority, title, body, " +
                        " source_type, source_id, scope_type, scope_id, action_url, actor_id, " +
                        " is_read, read_at, channels_sent, snoozed_until, created_at, archived_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orphan, 111L, 777L, "VILLAGE_EVENT", "HIGH", "タイトル-111", "本文-111",
                "VILLAGE_EVENT", 4242L, "TEAM", 555L, "/x", 999L,
                true, LocalDateTime.now().minusDays(READ_RETENTION_DAYS + 7), "[\"PUSH\"]", null,
                LocalDateTime.now().minusDays(READ_RETENTION_DAYS + 7), LocalDateTime.now());

        // 走行前は本体・archive の両方に居る（＝二重在庫）。
        assertThat(notificationCount(orphan)).as("走行前: 本体に孤児が残る").isEqualTo(1);
        assertThat(archiveCount(orphan)).as("走行前: archive にも既に居る").isEqualTo(1);

        cleanupBatchService.cleanupOldReadNotifications();

        // 再走で INSERT IGNORE され archived=0 でも、存在確認 DELETE が本体から掃き出す。
        assertThat(notificationCount(orphan)).as("再走後: 本体から消えて二重在庫が解消").isZero();
        assertThat(archiveCount(orphan)).as("再走後: archive は id あたり1本のまま").isEqualTo(1);
    }

    // ============================== AC-16 ==============================

    @Test
    @DisplayName("AC-16 移送対象0件でも例外なく正常終了する")
    void ac16_emptyRunCompletesWithoutError() {
        // 対象外（新しい）の行だけ置き、移送0件で走らせる。
        seedNotification(116L, true, READ_RETENTION_DAYS - 30);
        seedNotification(116L, false, 1);

        cleanupBatchService.cleanupOldReadNotifications();

        Long archiveTotal = jdbc.queryForObject("SELECT COUNT(*) FROM notifications_archive", Long.class);
        assertThat(archiveTotal).as("移送対象0件なら archive は空のまま").isZero();
    }

    // ============================== 内部ヘルパ ==============================

    /**
     * archive 行を全 NOT NULL 列充填で直接 INSERT する（退会波及 AC-7/8 の seed 用）。
     * archive 用 @Entity は持たない（D-2b UUIDv7 規約回避）ため JdbcTemplate 直で投入する。
     */
    private void insertArchiveRow(Long id, Long userId) {
        jdbc.update("INSERT INTO notifications_archive " +
                        "(id, user_id, organization_id, notification_type, priority, title, body, " +
                        " source_type, source_id, scope_type, scope_id, action_url, actor_id, " +
                        " is_read, read_at, channels_sent, snoozed_until, created_at, archived_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, userId, 777L, "VILLAGE_EVENT", "HIGH", "archive-" + id, "body-" + id,
                "VILLAGE_EVENT", 1L, NotificationScopeType.TEAM.name(), 555L, "/x", 999L,
                true, LocalDateTime.now().minusDays(200), "[\"PUSH\"]", null,
                LocalDateTime.now().minusDays(400), LocalDateTime.now());
    }
}
