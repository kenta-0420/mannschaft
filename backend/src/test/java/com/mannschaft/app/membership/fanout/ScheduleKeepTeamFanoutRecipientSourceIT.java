package com.mannschaft.app.membership.fanout;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import com.mannschaft.app.notification.fanout.FanoutRecipientSourceRegistry;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-017c「キープ変換通知の TEAM スコープ MEMBER 以上 全員への耐久 fan-out 配信」受け入れ条件の契約 IT。
 *
 * <p>MySQL 依存（keyset ページング・耐久ワーカー経路）を実測するため {@link AbstractMySqlIntegrationTest} 基底で回す。
 * 本 IT は非トランザクション（フィクスチャを実コミットする）。理由: fan-out ワーカーの受信者 keyset クエリ・
 * バルク INSERT はいずれも独立トランザクション（{@code REQUIRES_NEW}／素の接続）で走るため、テストの外側 TX に
 * 未コミットの memberships/users は<b>見えない</b>。{@link TeamFanoutRecipientSourceRedIT} と同じ作法。</p>
 *
 * <h2>scope_ref のフォーマット</h2>
 * <p>キープ用ジョブの {@code scope_ref} は {@code "teamId:actorId:creatorId"}（操作者・作成者を母集団から除くための
 * 埋め込み）。creator が匿名化済み（{@code created_by IS NULL}）なら 3 番目は {@code 0}。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-1 MEMBER 以上 全員（操作者除く）に届く → {@link #ac1_memberAndAboveReceiveExceptActorAndCreator()}</li>
 *   <li>AC-2 SUPPORTER/GUEST は届かない → {@link #ac2_supporterAndGuestExcluded()}</li>
 *   <li>AC-3 操作者本人には届かない → {@link #ac3_actorExcluded()}</li>
 *   <li>AC-6 他 TEAM の MEMBER には届かない → {@link #ac6_otherTeamNotDelivered()}</li>
 *   <li>AC-7 母集団0件でも 500 にせず DONE → {@link #ac7_emptyPopulationCompletesAsDone()}</li>
 *   <li>AC-8 enqueue は O(1)（1 行・母集団を数えない）→ {@link #ac8_enqueueIsO1()}</li>
 *   <li>AC-10 チャンク境界跨ぎでも欠落0・重複0 → {@link #ac10_midScaleNoLossNoDuplicate()}</li>
 *   <li>Registry 解決 → {@link #registryResolvesKeepSource()}</li>
 * </ul>
 */
@DisplayName("CMP-017c キープ変換通知 TEAM MEMBER 全員 fan-out 契約 IT")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleKeepTeamFanoutRecipientSourceIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ScheduleKeepTeamFanoutRecipientSourceIT.class);

    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private ScheduleKeepTeamFanoutRecipientSource keepSource;
    @Autowired
    private FanoutRecipientSourceRegistry registry;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private JdbcTemplate jdbc;

    // =====================================================================
    // Registry: scope_type="SCHEDULE_KEEP_TEAM" が本ソースを解決する
    // =====================================================================
    @Test
    @DisplayName("Registry は scope_type=\"SCHEDULE_KEEP_TEAM\" で本ソースを解決する")
    void registryResolvesKeepSource() {
        Optional<FanoutRecipientSource> resolved = registry.resolve(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE);
        assertThat(resolved).as("SCHEDULE_KEEP_TEAM が解決できる").isPresent();
        assertThat(resolved.get()).isInstanceOf(ScheduleKeepTeamFanoutRecipientSource.class);
    }

    // =====================================================================
    // AC-1: MEMBER 以上 全員（操作者・作成者を除く）に届く
    // =====================================================================
    @Test
    @DisplayName("AC-1: TEAM の MEMBER 以上 全員（操作者・作成者を除く）に配信される")
    void ac1_memberAndAboveReceiveExceptActorAndCreator() {
        long teamId = 9_101L;
        String type = "KEEP_FANOUT_IT_AC1";
        long actor = base(teamId) + 1;
        long creator = base(teamId) + 2;
        long m3 = base(teamId) + 3;
        long m4 = base(teamId) + 4;
        long m5 = base(teamId) + 5;
        seedMember(teamId, actor, RoleKind.MEMBER);
        seedMember(teamId, creator, RoleKind.MEMBER);
        seedMember(teamId, m3, RoleKind.MEMBER);
        seedMember(teamId, m4, RoleKind.MEMBER);
        seedMember(teamId, m5, RoleKind.MEMBER);

        worker.processOne(jobRepository.save(newKeepJob(scopeRef(teamId, actor, creator), type)));

        long delivered = countNotifications(type);
        log.info("[AC-1] delivered={}（期待=3: m3,m4,m5）", delivered);
        assertThat(delivered).as("AC-1: 操作者・作成者を除く MEMBER 以上 全員（3名）に届く").isEqualTo(3L);
        assertThat(deliveredTo(type, m3)).isTrue();
        assertThat(deliveredTo(type, m4)).isTrue();
        assertThat(deliveredTo(type, m5)).isTrue();
    }

    // =====================================================================
    // AC-2: SUPPORTER・GUEST には届かない（母集団段階で除外）
    // =====================================================================
    @Test
    @DisplayName("AC-2: SUPPORTER（role_kind=SUPPORTER）・GUEST（membership 無し）には届かない")
    void ac2_supporterAndGuestExcluded() {
        long teamId = 9_102L;
        String type = "KEEP_FANOUT_IT_AC2";
        long actor = base(teamId) + 1;
        long member = base(teamId) + 2;
        long supporter = base(teamId) + 3;
        long guest = base(teamId) + 4; // membership を持たない（users のみ）
        seedMember(teamId, actor, RoleKind.MEMBER);
        seedMember(teamId, member, RoleKind.MEMBER);
        seedMember(teamId, supporter, RoleKind.SUPPORTER);
        insertUser(guest, "ACTIVE", null); // membership なし

        worker.processOne(jobRepository.save(newKeepJob(scopeRef(teamId, actor, 0L), type)));

        log.info("[AC-2] delivered={} supporterGot={} guestGot={}",
                countNotifications(type), deliveredTo(type, supporter), deliveredTo(type, guest));
        assertThat(deliveredTo(type, member)).as("MEMBER には届く").isTrue();
        assertThat(deliveredTo(type, supporter)).as("AC-2: SUPPORTER には届かない").isFalse();
        assertThat(deliveredTo(type, guest)).as("AC-2: GUEST（membership 無し）には届かない").isFalse();
        assertThat(countNotifications(type)).as("AC-2: 配信は MEMBER の1名のみ").isEqualTo(1L);
    }

    // =====================================================================
    // AC-3: 変換操作者本人には届かない
    // =====================================================================
    @Test
    @DisplayName("AC-3: 変換操作者本人（actor）は MEMBER でも配信対象から除外される")
    void ac3_actorExcluded() {
        long teamId = 9_103L;
        String type = "KEEP_FANOUT_IT_AC3";
        long actor = base(teamId) + 1;
        long member = base(teamId) + 2;
        seedMember(teamId, actor, RoleKind.MEMBER);
        seedMember(teamId, member, RoleKind.MEMBER);

        worker.processOne(jobRepository.save(newKeepJob(scopeRef(teamId, actor, 0L), type)));

        assertThat(deliveredTo(type, actor)).as("AC-3: 操作者本人には届かない").isFalse();
        assertThat(deliveredTo(type, member)).as("他 MEMBER には届く").isTrue();
    }

    // =====================================================================
    // AC-6: 他 TEAM の MEMBER には届かない（scope_id 越境なし）
    // =====================================================================
    @Test
    @DisplayName("AC-6: scope_ref の TEAM 以外（別 TEAM・同 scope_id の ORG）には届かない")
    void ac6_otherTeamNotDelivered() {
        long teamA = 9_104L;
        long teamB = 9_105L;
        String type = "KEEP_FANOUT_IT_AC6";
        long actor = base(teamA) + 1;
        long aMember = base(teamA) + 2;
        long bMember = base(teamB) + 2;
        long orgMember = base(teamA) + 3; // scope_id=teamA だが ORGANIZATION
        seedMember(teamA, actor, RoleKind.MEMBER);
        seedMember(teamA, aMember, RoleKind.MEMBER);
        seedMember(teamB, bMember, RoleKind.MEMBER);
        seedMemberScoped(ScopeType.ORGANIZATION, teamA, orgMember, RoleKind.MEMBER);

        worker.processOne(jobRepository.save(newKeepJob(scopeRef(teamA, actor, 0L), type)));

        assertThat(deliveredTo(type, aMember)).as("teamA の MEMBER には届く").isTrue();
        assertThat(deliveredTo(type, bMember)).as("AC-6: 別 TEAM の MEMBER には届かない").isFalse();
        assertThat(deliveredTo(type, orgMember)).as("AC-6: 同一 scope_id の ORG には届かない").isFalse();
        assertThat(countNotifications(type)).as("AC-6: teamA の 1 名のみ").isEqualTo(1L);
    }

    // =====================================================================
    // AC-7: 母集団0件（操作者のみ）でも 500 にせず DONE 化
    // =====================================================================
    @Test
    @DisplayName("AC-7: 母集団0件（操作者を除くと空）でも例外にせず DONE で完了する")
    void ac7_emptyPopulationCompletesAsDone() {
        long teamId = 9_106L;
        String type = "KEEP_FANOUT_IT_AC7";
        long actor = base(teamId) + 1;
        seedMember(teamId, actor, RoleKind.MEMBER); // 操作者のみ

        NotificationFanoutJob job = jobRepository.save(newKeepJob(scopeRef(teamId, actor, 0L), type));
        worker.processOne(job);

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).as("AC-7: 母集団0件でも DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(countNotifications(type)).as("AC-7: 配信0件").isZero();
    }

    // =====================================================================
    // AC-8: enqueue は O(1)（母集団を数えず親ジョブ 1 行・shard_count=0）
    // =====================================================================
    @Test
    @DisplayName("AC-8: enqueue は母集団規模に依らず親ジョブ 1 行を INSERT（shard_count=0 の番人値）")
    void ac8_enqueueIsO1() {
        long teamId = 9_107L;
        String type = "KEEP_FANOUT_IT_AC8";
        long actor = base(teamId) + 1;
        for (int i = 2; i <= 40; i++) {
            seedMember(teamId, base(teamId) + i, RoleKind.MEMBER);
        }
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE, scopeRef(teamId, actor, 0L), type,
                sourceEvent, null, "日程が決まりました", "本文", NotificationPriority.NORMAL, "SCHEDULE", 1L, "/x", actor);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type=? AND scope_ref=? AND notification_type=?",
                Long.class, ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE, scopeRef(teamId, actor, 0L), type);
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE, scopeRef(teamId, actor, 0L), type, sourceEvent)
                .orElseThrow();
        assertThat(rows).as("AC-8: enqueue は 39 名の母集団でもジョブ行 1 件のみ").isEqualTo(1L);
        assertThat(job.getShardCount()).as("AC-8: shard_count=0（母集団未評価の番人値）").isEqualTo((short) 0);

        // 二重 enqueue は uk_fanout_idempotency で 1 件のまま（冪等）。
        jobService.enqueue(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE, scopeRef(teamId, actor, 0L), type,
                sourceEvent, null, "日程が決まりました", "本文", NotificationPriority.NORMAL, "SCHEDULE", 1L, "/x", actor);
        Long rows2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type=? AND scope_ref=? AND notification_type=?",
                Long.class, ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE, scopeRef(teamId, actor, 0L), type);
        assertThat(rows2).as("AC-8: 二重 enqueue でも冪等に 1 件").isEqualTo(1L);
    }

    // =====================================================================
    // AC-10: チャンク境界（CHUNK_SIZE=500）を跨ぐ中規模母集団でも欠落0・重複0
    //   キープ TEAM は単一シャード（countRecipients 既定=-1 → shard_count=1）経路のため、
    //   ここでは keyset ページングの全件網羅（欠落0・重複0）を CHUNK 境界跨ぎで実証する。
    //   大量データ生成回避のため 550 名（500+50）で 2 ページに跨がせる（設計 CMP-001⑤ の
    //   シャード担保自体は Org 経路の専用 IT が担保済み）。
    // =====================================================================
    @Test
    @DisplayName("AC-10: 550 名（CHUNK 境界跨ぎ）でも配信は欠落0・重複0（distinct=550）")
    void ac10_midScaleNoLossNoDuplicate() {
        long teamId = 9_108L;
        String type = "KEEP_FANOUT_IT_AC10";
        long actor = base(teamId) + 1;
        seedMember(teamId, actor, RoleKind.MEMBER);
        int n = 550;
        for (int i = 1; i <= n; i++) {
            seedMember(teamId, base(teamId) + 100 + i, RoleKind.MEMBER);
        }

        NotificationFanoutJob job = jobRepository.save(newKeepJob(scopeRef(teamId, actor, 0L), type));
        worker.processOne(job);

        long total = countNotifications(type);
        long distinct = countDistinctUsers(type);
        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-10] total={} distinct={} status={}", total, distinct, reloaded.getStatus());
        assertThat(total).as("AC-10: 配信総数 = 母集団（操作者除く 550 名）").isEqualTo((long) n);
        assertThat(distinct).as("AC-10: user_id は一意（重複0）").isEqualTo((long) n);
        assertThat(reloaded.getStatus()).as("AC-10: 完走で DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private static long base(long scopeId) {
        return scopeId * 1000L;
    }

    private static String scopeRef(long teamId, long actorId, long creatorId) {
        return teamId + ":" + actorId + ":" + creatorId;
    }

    private void seedMember(long teamId, long userId, RoleKind roleKind) {
        seedMemberScoped(ScopeType.TEAM, teamId, userId, roleKind);
    }

    private void seedMemberScoped(ScopeType scopeType, long scopeId, long userId, RoleKind roleKind) {
        insertUser(userId, "ACTIVE", null);
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .build());
    }

    /** users 行を挿入する（{@link TeamFanoutRecipientSourceRedIT#insertUser} と同じ全列充填）。 */
    private void insertUser(long userId, String status, LocalDateTime deletedAt) {
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
                userId, "keep-fanout-it-" + userId + "@example.test", "U" + userId, status, deletedAt, now, now);
    }

    private NotificationFanoutJob newKeepJob(String scopeRef, String type) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE)
                .scopeRef(scopeRef)
                .notificationType(type)
                .title("「合宿」の日程が決まりました")
                .body("合宿 が予定になりました。")
                .priority(NotificationPriority.NORMAL)
                .sourceType("SCHEDULE")
                .sourceId(1L)
                .actionUrl("/x")
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private long countNotifications(String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ?", Long.class, type);
        return c == null ? 0L : c;
    }

    private long countDistinctUsers(String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM notifications WHERE notification_type = ?", Long.class, type);
        return c == null ? 0L : c;
    }

    private boolean deliveredTo(String type, long userId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id = ?",
                Long.class, type, userId);
        return c != null && c > 0;
    }
}
