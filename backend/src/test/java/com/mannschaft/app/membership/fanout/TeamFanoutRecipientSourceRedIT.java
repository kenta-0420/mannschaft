package com.mannschaft.app.membership.fanout;

import com.mannschaft.app.membership.domain.LeaveReason;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知 fan-out 横展開 Wave-1「TEAM 耐久 fan-out」の受け入れ条件を符号化した red 試練。
 *
 * <p>MySQL 依存機能（キーセットページング・耐久ワーカー経路）を実測するため
 * {@link AbstractMySqlIntegrationTest} 基底で回す。TEAM 受信者供給の本体
 * （{@code TeamFanoutRecipientSource.nextPage}）は<b>出陣で実装</b>のため、受信者供給／配信を叩く AC が
 * 現行で FAIL（red）することを実 RUN（skipped=0）で確認した状態でコミットする。green 化は出陣が行う。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-1 keyset 法（TEAM・left_at IS NULL・user_id&gt;cursor 昇順）→ {@link #ac1_keysetReturnsActiveTeamMembersAscending()}（repo 土台＝green）</li>
 *   <li>AC-2 nextPage が scope_ref→keyset で TEAM メンバー供給 → {@link #ac2_nextPageSuppliesTeamMembers()}（nextPage 未実装＝red）</li>
 *   <li>AC-3 Registry が "TEAM" を解決 → {@link #ac3_registryResolvesTeamSource()}（配線土台＝green）</li>
 *   <li>AC-7 TEAM ジョブを worker 実行→受信者に通知 → {@link #ac7_workerDeliversToTeamMembers()}（nextPage 未実装＝red）</li>
 *   <li>AC-8 メンバー0件 TEAM は DONE で正常終了 → {@link #ac8_emptyTeamCompletesAsDone()}（nextPage 未実装＝red）</li>
 *   <li>AC-9 cursor=最終で空・limit ちょうどでページ跨ぎ継続 → {@link #ac9_keysetBoundaryPaging()}（repo 土台＝green）</li>
 *   <li>AC-10 途中 cursor から再走で欠落なし → {@link #ac10_resumeFromMidCursorNoLoss()}（nextPage 未実装＝red）</li>
 *   <li>AC-11 scope_id 厳密分離（別 TEAM/ORG 混入0）→ {@link #ac11_scopeIdStrictSeparation()}（nextPage 未実装＝red）</li>
 *   <li>AC-12 二重 enqueue は 1 ジョブ → {@link #ac12_doubleEnqueueIsIdempotent()}（enqueue 土台＝green）</li>
 *   <li>AC-13 受信者供給はページング（全件 List 化しない）→ {@link #ac13_keysetPagingNotFullList()}（repo 土台＝green）</li>
 * </ul>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。red が SKIP で緑に
 * 見えるのは無意味。実 RUN（skipped=0）で FAIL を確認すること。</p>
 */
@DisplayName("fan-out 横展開 Wave-1 受け入れ条件 red 試練（TEAM 耐久 fan-out）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TeamFanoutRecipientSourceRedIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TeamFanoutRecipientSourceRedIT.class);

    /** 1 チャンクを十分に大きく取り「全メンバーを 1 回で欲しい」ケースで使う limit。 */
    private static final int LARGE_LIMIT = 1_000;

    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private TeamFanoutRecipientSource teamSource;
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
    // AC-1 keyset 法: scope_type=TEAM・left_at IS NULL・user_id>cursor 昇順のみ返す（repo 土台=green）
    // =====================================================================
    @Test
    @DisplayName("AC-1 keyset 法は TEAM の現役メンバーを user_id 昇順で返す（退会者/別スコープを除外・repo 土台）")
    void ac1_keysetReturnsActiveTeamMembersAscending() {
        long teamId = 8_101L;
        List<Long> active = seedActiveTeamMembers(teamId, 3);       // 現役3名
        seedLeftTeamMember(teamId, base(teamId) + 90);              // 退会者（除外されるべき）
        seedActiveMember(ScopeType.ORGANIZATION, teamId, base(teamId) + 91); // 同一 scope_id の別スコープ（除外）
        seedActiveMember(ScopeType.TEAM, teamId + 1, base(teamId) + 92);     // 別 TEAM（除外）

        List<Long> page = membershipRepository.findActiveUserIdsByScopeKeyset(
                ScopeType.TEAM, teamId, 0L, PageRequest.of(0, LARGE_LIMIT));

        log.info("[AC-1] keyset 返却={}（現役={}）", page, active);
        assertThat(page)
                .as("AC-1: TEAM 現役メンバーのみ・user_id 昇順（退会者/ORG/別TEAM を含まない）")
                .containsExactlyElementsOf(active);
    }

    // =====================================================================
    // AC-2 nextPage: scope_ref(TEAM id 文字列)→keyset で TEAM メンバー供給（nextPage 未実装=red）
    // =====================================================================
    @Test
    @DisplayName("AC-2 TeamFanoutRecipientSource.nextPage が scope_ref から TEAM メンバーを供給する（未実装=red）")
    void ac2_nextPageSuppliesTeamMembers() {
        long teamId = 8_202L;
        List<Long> members = seedActiveTeamMembers(teamId, 5);

        // green: scope_ref=teamId から keyset で現役メンバーを供給。現行は nextPage 未実装ゆえ FAIL(red)。
        List<Long> page = teamSource.nextPage(String.valueOf(teamId), 0L, LARGE_LIMIT);

        log.info("[AC-2] nextPage 返却={}（期待={}）", page, members);
        assertThat(page).as("AC-2: nextPage は TEAM 現役メンバーを供給する").containsExactlyElementsOf(members);
    }

    // =====================================================================
    // AC-3 Registry が "TEAM" で TeamFanoutRecipientSource を解決する（配線土台=green）
    // =====================================================================
    @Test
    @DisplayName("AC-3 Registry は scope_type=\"TEAM\" で TeamFanoutRecipientSource を解決する（配線土台）")
    void ac3_registryResolvesTeamSource() {
        Optional<FanoutRecipientSource> resolved = registry.resolve(TeamFanoutRecipientSource.SCOPE_TYPE);

        log.info("[AC-3] resolve(\"TEAM\") present={} type={}",
                resolved.isPresent(), resolved.map(s -> s.getClass().getSimpleName()).orElse("-"));
        assertThat(resolved).as("AC-3: \"TEAM\" が解決できる").isPresent();
        assertThat(resolved.get())
                .as("AC-3: 解決される実装は TeamFanoutRecipientSource")
                .isInstanceOf(TeamFanoutRecipientSource.class);
    }

    // =====================================================================
    // AC-7 TEAM ジョブを worker 実行→受信者に通知が届く（実DB count・nextPage 未実装=red）
    // =====================================================================
    @Test
    @DisplayName("AC-7 TEAM ジョブを worker 実行すると現役メンバー全員に通知が届く（未実装=red）")
    void ac7_workerDeliversToTeamMembers() {
        long teamId = 8_207L;
        String type = "TEAM_FANOUT_IT_AC7";
        List<Long> members = seedActiveTeamMembers(teamId, 6);
        NotificationFanoutJob job = jobRepository.save(newTeamJob(teamId, type, 0L));

        // green: nextPage で供給されたメンバーへバルク配信。現行は nextPage 未実装ゆえ配信0件＝FAIL(red)。
        worker.processOne(job);

        long delivered = countNotifications(type);
        log.info("[AC-7] 配信件数={}（期待={}）", delivered, members.size());
        assertThat(delivered)
                .as("AC-7: TEAM 現役メンバー全員に通知が届く（現行は 0 件＝red）")
                .isEqualTo(members.size());
    }

    // =====================================================================
    // AC-8 メンバー0件 TEAM は nextPage 空・ジョブ DONE で正常終了（500 にしない・nextPage 未実装=red）
    // =====================================================================
    @Test
    @DisplayName("AC-8 メンバー0件 TEAM は空供給で DONE 完了する（例外で落とさない・未実装=red）")
    void ac8_emptyTeamCompletesAsDone() {
        long teamId = 8_208L; // メンバーを seed しない（0件）
        String type = "TEAM_FANOUT_IT_AC8";
        NotificationFanoutJob job = jobRepository.save(newTeamJob(teamId, type, 0L));

        // green: nextPage が空を返し markDone。現行は nextPage 未実装ゆえ DONE に到達せず＝FAIL(red)。
        worker.processOne(job);

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        long delivered = countNotifications(type);
        log.info("[AC-8] status={} delivered={}", reloaded.getStatus(), delivered);
        assertThat(reloaded.getStatus())
                .as("AC-8: 0件 TEAM は正常完了で DONE（例外・500 にしない）")
                .isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(delivered).as("AC-8: 0件 TEAM の配信は0件").isZero();
    }

    // =====================================================================
    // AC-9 cursor=最終 user_id で空返し（重複供給なし）・limit ちょうどでページ跨ぎ継続（境界・repo 土台=green）
    // =====================================================================
    @Test
    @DisplayName("AC-9 keyset は cursor=最終で空・limit 境界でページを跨いで継続する（境界・repo 土台）")
    void ac9_keysetBoundaryPaging() {
        long teamId = 8_209L;
        List<Long> members = seedActiveTeamMembers(teamId, 4); // [u1,u2,u3,u4] 昇順
        long u2 = members.get(1);
        long u4 = members.get(3);

        List<Long> firstPage = membershipRepository.findActiveUserIdsByScopeKeyset(
                ScopeType.TEAM, teamId, 0L, PageRequest.of(0, 2));
        List<Long> secondPage = membershipRepository.findActiveUserIdsByScopeKeyset(
                ScopeType.TEAM, teamId, u2, PageRequest.of(0, 2));
        List<Long> afterLast = membershipRepository.findActiveUserIdsByScopeKeyset(
                ScopeType.TEAM, teamId, u4, PageRequest.of(0, 2));

        log.info("[AC-9] page1={} page2={} afterLast={}", firstPage, secondPage, afterLast);
        assertThat(firstPage).as("AC-9: limit=2 ちょうどで先頭2件").containsExactly(members.get(0), members.get(1));
        assertThat(secondPage).as("AC-9: cursor=u2 から次の2件へページ跨ぎ").containsExactly(members.get(2), members.get(3));
        assertThat(afterLast).as("AC-9: cursor=最終 user_id で空返し（重複供給なし）").isEmpty();
    }

    // =====================================================================
    // AC-10 途中 cursor（クラッシュ相当）から再走で欠落なし・重複最小（at-least-once 代理・nextPage 未実装=red）
    // =====================================================================
    @Test
    @DisplayName("AC-10 途中 cursor から再走しても欠落なく全員に届く（クラッシュ再開・未実装=red）")
    void ac10_resumeFromMidCursorNoLoss() {
        long teamId = 8_210L;
        String type = "TEAM_FANOUT_IT_AC10";
        List<Long> members = seedActiveTeamMembers(teamId, 8); // N=8
        int k = 3;                                             // 先頭 k 件は crash 前に配信済み
        for (int i = 0; i < k; i++) {
            insertNotification(members.get(i), type);
        }
        long cursor = members.get(k - 1); // 処理済み末尾（k件目）の user_id
        NotificationFanoutJob job = jobRepository.save(newTeamJob(teamId, type, cursor));

        // green: cursor より後の (N-k) 件のみ供給し、合計 N・欠落なし・DONE。
        // 現行は nextPage 未実装ゆえ再開分が供給されず＝FAIL(red)。
        worker.processOne(job);

        long total = countNotifications(type);
        long distinct = countDistinctUsers(type);
        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-10] total={} distinct={} status={}（N={}）", total, distinct, reloaded.getStatus(), members.size());
        assertThat(total).as("AC-10: 再開後も合計 N 件（欠落なし）").isEqualTo(members.size());
        assertThat(distinct).as("AC-10: user_id は一意（cursor 以前の再生成による重複なし）").isEqualTo(members.size());
        assertThat(reloaded.getStatus()).as("AC-10: 完走で DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    // =====================================================================
    // AC-11 scope_id 厳密分離: 他 TEAM/ORG を混入させても対象 TEAM のメンバーのみ供給（nextPage 未実装=red）
    // =====================================================================
    @Test
    @DisplayName("AC-11 nextPage は scope_ref の TEAM のメンバーのみ供給する（別TEAM/ORG 混入0・未実装=red）")
    void ac11_scopeIdStrictSeparation() {
        long teamA = 8_211L;
        long teamB = 8_212L;
        List<Long> aMembers = seedActiveTeamMembers(teamA, 3);
        List<Long> bMembers = seedActiveTeamMembers(teamB, 3);
        // scope_id が teamA と同値の ORGANIZATION 行（scope_type 分離も突く）。
        long orgMember = base(teamA) + 95;
        seedActiveMember(ScopeType.ORGANIZATION, teamA, orgMember);

        // green: teamA の現役メンバーのみ。現行は nextPage 未実装ゆえ FAIL(red)。
        List<Long> page = teamSource.nextPage(String.valueOf(teamA), 0L, LARGE_LIMIT);

        log.info("[AC-11] teamA 供給={}（teamB={} org={} を含まない想定）", page, bMembers, orgMember);
        assertThat(page).as("AC-11: teamA の現役メンバーのみ").containsExactlyInAnyOrderElementsOf(aMembers);
        assertThat(page).as("AC-11: 別 TEAM は混入しない").doesNotContainAnyElementsOf(bMembers);
        assertThat(page).as("AC-11: 同一 scope_id の ORG は混入しない").doesNotContain(orgMember);
    }

    // =====================================================================
    // AC-12 二重 enqueue は uk_fanout_idempotency で 1 ジョブ（enqueue 土台=green）
    // =====================================================================
    @Test
    @DisplayName("AC-12 同一(TEAM,scope_ref,type,source_event) の二重 enqueue はジョブ1件（冪等・土台）")
    void ac12_doubleEnqueueIsIdempotent() {
        long teamId = 8_220L;
        String type = "TEAM_FANOUT_IT_AC12";
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(TeamFanoutRecipientSource.SCOPE_TYPE, String.valueOf(teamId), type, sourceEvent, null,
                "AC-12 冪等", "本文", NotificationPriority.NORMAL, "TEAM_FANOUT_IT", null, "/x", null);
        jobService.enqueue(TeamFanoutRecipientSource.SCOPE_TYPE, String.valueOf(teamId), type, sourceEvent, null,
                "AC-12 冪等", "本文", NotificationPriority.NORMAL, "TEAM_FANOUT_IT", null, "/x", null);

        Optional<NotificationFanoutJob> found = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        TeamFanoutRecipientSource.SCOPE_TYPE, String.valueOf(teamId), type, sourceEvent);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type = ? AND scope_ref = ? "
                        + "AND notification_type = ?",
                Long.class, TeamFanoutRecipientSource.SCOPE_TYPE, String.valueOf(teamId), type);

        log.info("[AC-12] present={} rows={}", found.isPresent(), rows);
        assertThat(found).as("AC-12: 冪等キーのジョブが実在").isPresent();
        assertThat(rows).as("AC-12: 二重 enqueue でもジョブ行はちょうど1件（uk_fanout_idempotency）").isEqualTo(1L);
    }

    // =====================================================================
    // AC-13 受信者供給はキーセットページング（全件 List 化でなく分割）・索引被覆の代理検証（repo 土台=green）
    // =====================================================================
    @Test
    @DisplayName("AC-13 keyset は limit で分割供給し、cursor 反復で全件を重複なく列挙する（全件 List 化しない・土台）")
    void ac13_keysetPagingNotFullList() {
        long teamId = 8_213L;
        List<Long> members = seedActiveTeamMembers(teamId, 5);
        int chunk = 2;

        List<Long> collected = new ArrayList<>();
        long cursor = 0L;
        int firstChunkSize = -1;
        while (true) {
            List<Long> page = membershipRepository.findActiveUserIdsByScopeKeyset(
                    ScopeType.TEAM, teamId, cursor, PageRequest.of(0, chunk));
            if (firstChunkSize < 0) {
                firstChunkSize = page.size();
            }
            if (page.isEmpty()) {
                break;
            }
            collected.addAll(page);
            cursor = page.get(page.size() - 1);
        }

        log.info("[AC-13] firstChunkSize={} collected={}（全メンバー={}）", firstChunkSize, collected, members);
        assertThat(firstChunkSize)
                .as("AC-13: 1 回の供給は limit で分割される（全件を一度に返さない）")
                .isEqualTo(chunk);
        assertThat(collected)
                .as("AC-13: cursor 反復で全メンバーを重複なく列挙する")
                .containsExactlyElementsOf(members);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /** scope_id に対応する user_id レンジの下端（＝scopeId*1000）。 */
    private static long base(long scopeId) {
        return scopeId * 1000L;
    }

    /** 指定 TEAM に現役メンバーを count 名 seed し、user_id 昇順のリストを返す（user_id = base+1..base+count）。 */
    private List<Long> seedActiveTeamMembers(long teamId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long userId = base(teamId) + i;
            seedActiveMember(ScopeType.TEAM, teamId, userId);
            ids.add(userId);
        }
        return ids;
    }

    private void seedActiveMember(ScopeType scopeType, long scopeId, long userId) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build());
    }

    private void seedLeftTeamMember(long teamId, long userId) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(teamId)
                .leftAt(LocalDateTime.now().minusDays(1))
                .leaveReason(LeaveReason.SELF)
                .build());
    }

    private NotificationFanoutJob newTeamJob(long teamId, String type, long cursor) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(TeamFanoutRecipientSource.SCOPE_TYPE)
                .scopeRef(String.valueOf(teamId))
                .notificationType(type)
                .title("TEAM fan-out IT")
                .priority(NotificationPriority.NORMAL)
                .sourceType("TEAM_FANOUT_IT")
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(cursor)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void insertNotification(long userId, String type) {
        jdbc.update("INSERT INTO notifications "
                        + "(user_id, notification_type, priority, title, source_type, scope_type, is_read, created_at) "
                        + "VALUES (?, ?, 'NORMAL', ?, 'TEAM_FANOUT_IT', 'SYSTEM', 0, ?)",
                userId, type, "pre-" + type, LocalDateTime.now());
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
}
