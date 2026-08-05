package com.mannschaft.app.organization.fanout;

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
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知 fan-out 横展開 Wave-2「ORGANIZATION 耐久 fan-out」の受け入れ条件を符号化した red 試練。
 *
 * <p>MySQL 依存機能（ORG 版キーセット再帰 CTE・耐久ワーカー経路）を実測するため
 * {@link AbstractMySqlIntegrationTest} 基底で回す。</p>
 *
 * <h2>検証の二層</h2>
 * <ul>
 *   <li><b>キーセット層</b>: {@link UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset}
 *       を直接叩く回帰テスト（AC-2/3/4/9/13/14/15）。母集団条件・再帰展開・純 SUPPORTER 除外を検証する。</li>
 *   <li><b>配信層</b>: {@link OrgFanoutRecipientSource}／Registry 解決／ワーカー配信／enqueue の
 *       include_supporters 運搬（AC-1/7/8/10/11/5）。ORG ジョブが受信者ページングを経て現役全員へ届くことを検証する。</li>
 * </ul>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-1 scopeType()=ORGANIZATION・Registry 解決 → {@link #ac1_registryResolvesOrgSource()}</li>
 *   <li>AC-2 直属∪配下再帰チームを user_id 昇順で供給 → {@link #ac2_recursiveUnionAscending()}</li>
 *   <li>AC-3 includeSupporters 制御（純SUPPORTER除外／MEMBER優先）→ {@link #ac3_includeSupportersFilter()}</li>
 *   <li>AC-4 母集団は users ACTIVE・未削除＋tom ACTIVE → {@link #ac4_populationActiveOnly()}</li>
 *   <li>AC-5 include_supporters 列が enqueue 経由でジョブに保存 → {@link #ac5_enqueueCarriesIncludeSupporters()}
 *       ／列往復は {@link #ac5_includeSupportersColumnRoundTrips()}</li>
 *   <li>AC-7 ORG ジョブを worker 実行→現役全員に配信 → {@link #ac7_workerDeliversToOrgMembers()}</li>
 *   <li>AC-8 メンバー0件 ORG は DONE 正常終了 → {@link #ac8_emptyOrgCompletesAsDone()}</li>
 *   <li>AC-9 cursor=最終で空・limit 境界でページ跨ぎ → {@link #ac9_keysetBoundaryPaging()}</li>
 *   <li>AC-10 途中 cursor 再走で欠落なし → {@link #ac10_resumeFromMidCursorNoLoss()}</li>
 *   <li>AC-11 別 org のツリーは混入0 → {@link #ac11_orgTreeStrictSeparation()}</li>
 *   <li>AC-12 二重 enqueue は 1 ジョブ → {@link #ac12_doubleEnqueueIsIdempotent()}</li>
 *   <li>AC-13 キーセットで分割供給（全件 List 化しない）→ {@link #ac13_keysetPagingNotFullList()}</li>
 *   <li>AC-14 再帰深さ上限でサイクル/深ネスト停止 → {@link #ac14_recursionDepthBounded()}</li>
 *   <li>AC-15 配下チーム展開は tom.status=ACTIVE のみ → {@link #ac15_leftTeamMembersExcluded()}</li>
 * </ul>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。red が SKIP で緑に
 * 見えるのは無意味。実 RUN（skipped=0）で FAIL を確認すること。</p>
 */
@DisplayName("fan-out 横展開 Wave-2 受け入れ条件 red 試練（ORGANIZATION 耐久 fan-out）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrgFanoutRecipientSourceRedIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrgFanoutRecipientSourceRedIT.class);

    /** 1 チャンクを十分に大きく取り「全メンバーを 1 回で欲しい」ケースで使う limit。 */
    private static final int LARGE_LIMIT = 1_000;
    /** ORG 再帰展開の通常上限。 */
    private static final int MAX_DEPTH = 32;

    /** slug 一意制約（uq_organizations_slug）衝突を避けるための連番ジェネレータ。 */
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Autowired
    private MembershipRepository membershipRepository;
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
    // AC-1 Registry が "ORGANIZATION" で OrgFanoutRecipientSource を解決する
    // =====================================================================
    @Test
    @DisplayName("AC-1 Registry は scope_type=\"ORGANIZATION\" で OrgFanoutRecipientSource を解決する")
    void ac1_registryResolvesOrgSource() {
        // Registry が @Component 登録済みの OrgFanoutRecipientSource を "ORGANIZATION" で解決する。
        Optional<FanoutRecipientSource> resolved = registry.resolve(OrgFanoutRecipientSource.SCOPE_TYPE);

        log.info("[AC-1] resolve(\"ORGANIZATION\") present={} type={}",
                resolved.isPresent(), resolved.map(s -> s.getClass().getSimpleName()).orElse("-"));
        assertThat(resolved).as("AC-1: \"ORGANIZATION\" が解決できる").isPresent();
        assertThat(resolved.get())
                .as("AC-1: 解決される実装は OrgFanoutRecipientSource")
                .isInstanceOf(OrgFanoutRecipientSource.class);
    }

    // =====================================================================
    // AC-2 直属∪配下再帰チームのメンバーを user_id 昇順・分割供給
    // =====================================================================
    @Test
    @DisplayName("AC-2 keyset は直属メンバー∪配下再帰チームメンバーを user_id 昇順で返す（repo 土台）")
    void ac2_recursiveUnionAscending() {
        long seed = 9_102L;
        long rootOrg = createOrg(null);
        long childOrg = createOrg(rootOrg);
        long team = 77_102L;
        seedTeamOrgMembership(team, rootOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        long uDirect = base(seed) + 1;   // root へ直属（organization_id）
        long uChild = base(seed) + 2;    // 配下組織 childOrg へ直属
        long uTeam = base(seed) + 3;     // root 配下の team 経由（team_id）
        seedOrgMember(rootOrg, uDirect, "ACTIVE", false);
        seedOrgMember(childOrg, uChild, "ACTIVE", false);
        seedTeamMember(team, uTeam, "ACTIVE", false);

        List<Long> page = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                rootOrg, true, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));

        log.info("[AC-2] 供給={}（期待 昇順 [{},{},{}]）", page, uDirect, uChild, uTeam);
        assertThat(page)
                .as("AC-2: 直属∪配下組織∪配下チームのメンバーを user_id 昇順で返す")
                .containsExactly(uDirect, uChild, uTeam);
    }

    // =====================================================================
    // AC-3 includeSupporters 制御: false=純SUPPORTER除外（MEMBER優先）／true=含める
    // =====================================================================
    @Test
    @DisplayName("AC-3 includeSupporters=false は純SUPPORTERを除外し MEMBER 兼務は残す／true は含める（repo 土台）")
    void ac3_includeSupportersFilter() {
        long seed = 9_103L;
        long org = createOrg(null);
        long pureMember = base(seed) + 1;    // MEMBER のみ
        long pureSupporter = base(seed) + 2; // SUPPORTER のみ（false で除外されるべき）
        long mixed = base(seed) + 3;         // SUPPORTER かつ MEMBER（MEMBER 優先で残す）
        for (long u : new long[] {pureMember, pureSupporter, mixed}) {
            seedOrgMember(org, u, "ACTIVE", false);
        }
        seedMembership(pureMember, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);
        seedMembership(pureSupporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER);
        seedMembership(mixed, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER);
        seedMembership(mixed, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);

        List<Long> excluded = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, false, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));
        List<Long> included = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));

        log.info("[AC-3] false={} true={}", excluded, included);
        assertThat(excluded)
                .as("AC-3: includeSupporters=false は純SUPPORTER を除外し MEMBER/兼務は残す")
                .containsExactly(pureMember, mixed)
                .doesNotContain(pureSupporter);
        assertThat(included)
                .as("AC-3: includeSupporters=true は SUPPORTER も含め全員")
                .containsExactly(pureMember, pureSupporter, mixed);
    }

    // =====================================================================
    // AC-4 母集団は users.deleted_at IS NULL AND status='ACTIVE'（停止/退会ユーザーは非受信）
    // =====================================================================
    @Test
    @DisplayName("AC-4 停止(FROZEN)・論理削除済ユーザーは user_role 開存でも供給しない（repo 土台）")
    void ac4_populationActiveOnly() {
        long seed = 9_104L;
        long org = createOrg(null);
        long active1 = base(seed) + 1;
        long frozen = base(seed) + 2;   // user_role 開存だが status=FROZEN
        long deleted = base(seed) + 3;  // user_role 開存だが deleted_at 済
        long active2 = base(seed) + 4;
        seedOrgMember(org, active1, "ACTIVE", false);
        seedOrgMember(org, frozen, "FROZEN", false);
        seedOrgMember(org, deleted, "ACTIVE", true);
        seedOrgMember(org, active2, "ACTIVE", false);

        List<Long> page = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));

        log.info("[AC-4] 供給={}（期待 ACTIVE未削除のみ [{},{}]）", page, active1, active2);
        assertThat(page)
                .as("AC-4: 停止・論理削除済ユーザーは user_role 開存でも通知対象から除外される")
                .containsExactly(active1, active2)
                .doesNotContain(frozen, deleted);
    }

    // =====================================================================
    // AC-5 include_supporters 列が enqueue 経由でジョブに保存される
    // =====================================================================
    @Test
    @DisplayName("AC-5 enqueue した ORG ジョブに include_supporters=false が運搬される")
    void ac5_enqueueCarriesIncludeSupporters() {
        long org = 9_205L;
        String type = "ORG_FANOUT_IT_AC5";
        UUID sourceEvent = UUID.randomUUID();

        // 「SUPPORTER を除外して配信したい」意図の enqueue。13 引数版で includeSupporters=false を運搬し、
        // ジョブ列 include_supporters に false が保存される。
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type, sourceEvent, null,
                "AC-5 include_supporters 運搬", "本文", NotificationPriority.NORMAL, "ORG_FANOUT_IT", null, "/x", null,
                false);

        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type, sourceEvent)
                .orElseThrow();

        log.info("[AC-5] includeSupporters={}（期待 false）", job.getIncludeSupporters());
        assertThat(job.getIncludeSupporters())
                .as("AC-5: SUPPORTER 除外意図の enqueue はジョブに include_supporters=false を保存する")
                .isFalse();
    }

    // =====================================================================
    // AC-5(土台) include_supporters 列は builder→DB 往復で保持される
    // =====================================================================
    @Test
    @DisplayName("AC-5(土台) include_supporters 列は false を DB 往復で保持する（V175 列存在の土台）")
    void ac5_includeSupportersColumnRoundTrips() {
        long org = 9_215L;
        NotificationFanoutJob saved = jobRepository.save(
                newOrgJob(org, "ORG_FANOUT_IT_AC5B", 0L, Boolean.FALSE));

        NotificationFanoutJob reloaded = jobRepository.findById(saved.getId()).orElseThrow();

        log.info("[AC-5土台] includeSupporters={}", reloaded.getIncludeSupporters());
        assertThat(reloaded.getIncludeSupporters())
                .as("AC-5土台: include_supporters 列は false を保持する")
                .isFalse();
    }

    // =====================================================================
    // AC-7 ORG ジョブを worker 実行→現役メンバー全員に通知が届く
    // =====================================================================
    @Test
    @DisplayName("AC-7 ORG ジョブを worker 実行すると現役メンバー全員に通知が届く")
    void ac7_workerDeliversToOrgMembers() {
        long seed = 9_107L;
        long org = createOrg(null);
        String type = "ORG_FANOUT_IT_AC7";
        List<Long> members = seedOrgMembers(org, seed, 6);
        jobRepository.save(newOrgJob(org, type, 0L, Boolean.TRUE));

        // Registry が解決した OrgFanoutRecipientSource が nextPage で供給したメンバーへバルク配信する。
        // processReady は内部で例外を握るため、配信0件になった場合もテスト自体は error にならず
        // 配信件数の assertion で不一致として検出する。
        worker.processReady();

        long delivered = countNotifications(type);
        log.info("[AC-7] 配信件数={}（期待={}）", delivered, members.size());
        assertThat(delivered)
                .as("AC-7: ORG 現役メンバー全員に通知が届く")
                .isEqualTo(members.size());
    }

    // =====================================================================
    // AC-8 メンバー0件 ORG は空供給で DONE 正常終了（500 にしない）
    // =====================================================================
    @Test
    @DisplayName("AC-8 メンバー0件 ORG は空供給で DONE 完了する（例外で落とさない）")
    void ac8_emptyOrgCompletesAsDone() {
        long org = createOrg(null); // メンバーを seed しない（0件）
        String type = "ORG_FANOUT_IT_AC8";
        NotificationFanoutJob job = jobRepository.save(newOrgJob(org, type, 0L, Boolean.TRUE));

        // nextPage が空を返し markDone に到達する。
        worker.processReady();

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-8] status={}", reloaded.getStatus());
        assertThat(reloaded.getStatus())
                .as("AC-8: 0件 ORG は正常完了で DONE（例外・500 にしない）")
                .isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    // =====================================================================
    // AC-9 cursor=最終 user_id で空返し・limit ちょうどでページ跨ぎ継続（境界）
    // =====================================================================
    @Test
    @DisplayName("AC-9 keyset は cursor=最終で空・limit 境界でページを跨いで継続する（境界・repo 土台）")
    void ac9_keysetBoundaryPaging() {
        long seed = 9_109L;
        long org = createOrg(null);
        List<Long> members = seedOrgMembers(org, seed, 4); // [u1,u2,u3,u4] 昇順
        long u2 = members.get(1);
        long u4 = members.get(3);

        List<Long> firstPage = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, 0L, PageRequest.of(0, 2));
        List<Long> secondPage = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, u2, PageRequest.of(0, 2));
        List<Long> afterLast = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, u4, PageRequest.of(0, 2));

        log.info("[AC-9] page1={} page2={} afterLast={}", firstPage, secondPage, afterLast);
        assertThat(firstPage).as("AC-9: limit=2 ちょうどで先頭2件").containsExactly(members.get(0), members.get(1));
        assertThat(secondPage).as("AC-9: cursor=u2 から次の2件へページ跨ぎ").containsExactly(members.get(2), members.get(3));
        assertThat(afterLast).as("AC-9: cursor=最終 user_id で空返し（重複供給なし）").isEmpty();
    }

    // =====================================================================
    // AC-10 途中 cursor（クラッシュ相当）から再走で欠落なし（at-least-once 代理）
    // =====================================================================
    @Test
    @DisplayName("AC-10 途中 cursor から再走しても欠落なく全員に届く（クラッシュ再開）")
    void ac10_resumeFromMidCursorNoLoss() {
        long seed = 9_110L;
        long org = createOrg(null);
        String type = "ORG_FANOUT_IT_AC10";
        List<Long> members = seedOrgMembers(org, seed, 8); // N=8
        int k = 3;                                          // 先頭 k 件は crash 前に配信済み
        for (int i = 0; i < k; i++) {
            insertNotification(members.get(i), type);
        }
        long cursor = members.get(k - 1); // 処理済み末尾（k件目）の user_id
        NotificationFanoutJob job = jobRepository.save(newOrgJob(org, type, cursor, Boolean.TRUE));

        // cursor より後の (N-k) 件のみ供給し、合計 N・欠落なし・DONE で完走する。
        worker.processReady();

        long total = countNotifications(type);
        long distinct = countDistinctUsers(type);
        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-10] total={} distinct={} status={}（N={}）", total, distinct, reloaded.getStatus(), members.size());
        assertThat(total).as("AC-10: 再開後も合計 N 件（欠落なし）").isEqualTo(members.size());
        assertThat(distinct).as("AC-10: user_id は一意（cursor 以前の再生成による重複なし）").isEqualTo(members.size());
        assertThat(reloaded.getStatus()).as("AC-10: 完走で DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    // =====================================================================
    // AC-11 別 org のツリーを混入させても対象 org ツリーのメンバーのみ配信
    // =====================================================================
    @Test
    @DisplayName("AC-11 worker は scope_ref の org ツリーのメンバーのみ配信する（別 org 混入0）")
    void ac11_orgTreeStrictSeparation() {
        long seedA = 9_111L;
        long seedB = 9_112L;
        long orgA = createOrg(null);
        long orgB = createOrg(null);
        String type = "ORG_FANOUT_IT_AC11";
        List<Long> aMembers = seedOrgMembers(orgA, seedA, 3);
        List<Long> bMembers = seedOrgMembers(orgB, seedB, 3);
        jobRepository.save(newOrgJob(orgA, type, 0L, Boolean.TRUE)); // orgA のみ対象

        // orgA ツリーの現役メンバーのみに配信し、orgB へは混入しない。
        worker.processReady();

        long deliveredToA = countNotificationsForUsers(type, aMembers);
        long deliveredToB = countNotificationsForUsers(type, bMembers);
        log.info("[AC-11] A配信={} B配信={}", deliveredToA, deliveredToB);
        assertThat(deliveredToA).as("AC-11: orgA の現役メンバー全員に届く").isEqualTo(aMembers.size());
        assertThat(deliveredToB).as("AC-11: 別 org（orgB）には 1 件も届かない").isZero();
    }

    // =====================================================================
    // AC-12 二重 enqueue は uk_fanout_idempotency で 1 ジョブ
    // =====================================================================
    @Test
    @DisplayName("AC-12 同一(ORGANIZATION,scope_ref,type,source_event) の二重 enqueue はジョブ1件（冪等・土台）")
    void ac12_doubleEnqueueIsIdempotent() {
        long org = 9_220L;
        String type = "ORG_FANOUT_IT_AC12";
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type, sourceEvent, null,
                "AC-12 冪等", "本文", NotificationPriority.NORMAL, "ORG_FANOUT_IT", null, "/x", null);
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type, sourceEvent, null,
                "AC-12 冪等", "本文", NotificationPriority.NORMAL, "ORG_FANOUT_IT", null, "/x", null);

        Optional<NotificationFanoutJob> found = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type, sourceEvent);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE scope_type = ? AND scope_ref = ? "
                        + "AND notification_type = ?",
                Long.class, OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(org), type);

        log.info("[AC-12] present={} rows={}", found.isPresent(), rows);
        assertThat(found).as("AC-12: 冪等キーのジョブが実在").isPresent();
        assertThat(rows).as("AC-12: 二重 enqueue でもジョブ行はちょうど1件（uk_fanout_idempotency）").isEqualTo(1L);
    }

    // =====================================================================
    // AC-13 受信者供給はキーセットページング（全件 List 化でなく分割）・全件重複なく列挙
    // =====================================================================
    @Test
    @DisplayName("AC-13 keyset は limit で分割供給し、cursor 反復で全件を重複なく列挙する（全件 List 化しない・土台）")
    void ac13_keysetPagingNotFullList() {
        long seed = 9_113L;
        long org = createOrg(null);
        List<Long> members = seedOrgMembers(org, seed, 5);
        int chunk = 2;

        List<Long> collected = new ArrayList<>();
        long cursor = 0L;
        int firstChunkSize = -1;
        while (true) {
            List<Long> page = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                    org, true, MAX_DEPTH, cursor, PageRequest.of(0, chunk));
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
    // AC-14 再帰深さ上限でサイクル/深ネストを停止（無限ループしない）
    // =====================================================================
    @Test
    @DisplayName("AC-14 自己参照サイクルでも maxDepth で停止し完了する／深ネストは maxDepth で打ち切る（repo 土台）")
    void ac14_recursionDepthBounded() {
        long seed = 9_114L;
        // ① 自己参照サイクル（parent = 自分）でも無限ループせず完了する。
        long selfCycleOrg = createOrg(null);
        setParent(selfCycleOrg, selfCycleOrg);
        long cycleMember = base(seed) + 1;
        seedOrgMember(selfCycleOrg, cycleMember, "ACTIVE", false);

        List<Long> cyclePage = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                selfCycleOrg, true, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));
        log.info("[AC-14①] self-cycle 供給={}", cyclePage);
        assertThat(cyclePage)
                .as("AC-14①: 自己参照サイクルでも maxDepth で停止し、メンバーを重複なく1件返す（無限ループしない）")
                .containsExactly(cycleMember);

        // ② 深ネスト root→c1→c2 を maxDepth=1 で打ち切る（root と直下 c1 のみ・c2 は非展開）。
        long root = createOrg(null);
        long c1 = createOrg(root);
        long c2 = createOrg(c1);
        long uRoot = base(seed) + 11;
        long uC1 = base(seed) + 12;
        long uC2 = base(seed) + 13;
        seedOrgMember(root, uRoot, "ACTIVE", false);
        seedOrgMember(c1, uC1, "ACTIVE", false);
        seedOrgMember(c2, uC2, "ACTIVE", false);

        List<Long> depth1 = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                root, true, 1, 0L, PageRequest.of(0, LARGE_LIMIT));
        log.info("[AC-14②] maxDepth=1 供給={}（c2 メンバー {} は非展開の想定）", depth1, uC2);
        assertThat(depth1)
                .as("AC-14②: maxDepth=1 は root と直下 c1 のみ展開し、深い c2 メンバーは含めない")
                .containsExactly(uRoot, uC1)
                .doesNotContain(uC2);
    }

    // =====================================================================
    // AC-15 配下チーム展開は tom.status='ACTIVE' のみ（脱退チーム所属者は非受信）
    // =====================================================================
    @Test
    @DisplayName("AC-15 配下チーム展開は tom.status=ACTIVE のみ（PENDING/脱退チーム所属者は非受信・repo 土台）")
    void ac15_leftTeamMembersExcluded() {
        long seed = 9_115L;
        long org = createOrg(null);
        long activeTeam = 77_115L;
        long pendingTeam = 78_115L;
        seedTeamOrgMembership(activeTeam, org, TeamOrgMembershipEntity.Status.ACTIVE);
        seedTeamOrgMembership(pendingTeam, org, TeamOrgMembershipEntity.Status.PENDING); // 未承認＝非展開

        long uActive = base(seed) + 1; // ACTIVE チーム所属（受信）
        long uPending = base(seed) + 2; // PENDING チーム所属（非受信）
        seedTeamMember(activeTeam, uActive, "ACTIVE", false);
        seedTeamMember(pendingTeam, uPending, "ACTIVE", false);

        List<Long> page = userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                org, true, MAX_DEPTH, 0L, PageRequest.of(0, LARGE_LIMIT));

        log.info("[AC-15] 供給={}（uActive={} のみ・uPending={} は除外の想定）", page, uActive, uPending);
        assertThat(page)
                .as("AC-15: tom.status=ACTIVE のチーム所属者のみ供給し、PENDING チーム所属者は除外する")
                .containsExactly(uActive)
                .doesNotContain(uPending);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /** scope seed に対応する user_id レンジの下端（＝seed*1000）。org id とは独立に一意な user_id 帯を確保する。 */
    private static long base(long seed) {
        return seed * 1000L;
    }

    /** 組織を 1 件作成し、生成された org id を返す（slug は連番で一意化）。 */
    private long createOrg(Long parentOrganizationId) {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("org-fanout-it-" + SLUG_SEQ.incrementAndGet())
                .name("fan-out IT org")
                .orgType(OrganizationEntity.OrgType.COMMUNITY)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return org.getId();
    }

    /** 既存組織の parent_organization_id を更新する（サイクル生成用）。 */
    private void setParent(long orgId, long parentOrgId) {
        jdbc.update("UPDATE organizations SET parent_organization_id = ? WHERE id = ?", parentOrgId, orgId);
    }

    /** 指定 org に現役メンバーを count 名 seed し、user_id 昇順のリストを返す（user_id = base+1..base+count）。 */
    private List<Long> seedOrgMembers(long orgId, long seed, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long userId = base(seed) + i;
            seedOrgMember(orgId, userId, "ACTIVE", false);
            ids.add(userId);
        }
        return ids;
    }

    /** 組織へ直属する user_role を持つメンバーを seed（対応する ACTIVE/削除状態のユーザー本体も作る）。 */
    private void seedOrgMember(long orgId, long userId, String userStatus, boolean deleted) {
        insertUser(userId, userStatus, deleted ? LocalDateTime.now().minusHours(1) : null);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(3L)
                .organizationId(orgId)
                .build());
    }

    /** チームへ所属する user_role（team_id 指定）を持つメンバーを seed。 */
    private void seedTeamMember(long teamId, long userId, String userStatus, boolean deleted) {
        insertUser(userId, userStatus, deleted ? LocalDateTime.now().minusHours(1) : null);
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(3L)
                .teamId(teamId)
                .build());
    }

    /** team_org_memberships（チーム→組織の所属）を 1 件 seed する。 */
    private void seedTeamOrgMembership(long teamId, long orgId, TeamOrgMembershipEntity.Status status) {
        teamOrgMembershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build());
    }

    /** memberships（多態 1 表・role_kind 判定用）を 1 件 seed する。 */
    private void seedMembership(long userId, ScopeType scopeType, long scopeId, RoleKind roleKind) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now().minusDays(1))
                .build());
    }

    /**
     * users 行を挿入する。test profile は {@code ddl-auto:create} で {@code users} 表を {@code UserEntity} から
     * 生成するため、{@code @Column(nullable = false)} かつ DB default を持たない列を<b>すべて</b>埋めないと
     * error 1364 で INSERT が落ちる（TEAM 版 {@code TeamFanoutRecipientSourceRedIT#insertUser} と同一列集合）。
     */
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
                userId, "org-fanout-it-" + userId + "@example.test", "U" + userId, status, deletedAt, now, now);
    }

    private NotificationFanoutJob newOrgJob(long orgId, String type, long cursor, Boolean includeSupporters) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(OrgFanoutRecipientSource.SCOPE_TYPE)
                .scopeRef(String.valueOf(orgId))
                .notificationType(type)
                .title("ORG fan-out IT")
                .priority(NotificationPriority.NORMAL)
                .sourceType("ORG_FANOUT_IT")
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(cursor)
                .includeSupporters(includeSupporters)
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
                        + "VALUES (?, ?, 'NORMAL', ?, 'ORG_FANOUT_IT', 'SYSTEM', 0, ?)",
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

    private long countNotificationsForUsers(String type, List<Long> userIds) {
        long total = 0L;
        for (Long userId : userIds) {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id = ?",
                    Long.class, type, userId);
            total += (c == null ? 0L : c);
        }
        return total;
    }
}
