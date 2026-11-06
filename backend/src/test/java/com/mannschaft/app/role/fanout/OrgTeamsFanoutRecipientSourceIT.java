package com.mannschaft.app.role.fanout;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSourceRegistry;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceEntity;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceTeamEntity;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceTeamRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 部隊 6-E 試練 — 受信者ソース {@code ORGANIZATION_TEAMS}（設計書 §8.5.2）。
 *
 * <p>宛先集合の表（{@code notification_fanout_audiences}・{@code notification_fanout_audience_teams}）を
 * 実 MySQL に置き、{@link OrgTeamsFanoutRecipientSource#nextPage} を直接叩いて母集団・シャード・異常系を確かめる。
 * Repository・認可はモックしない。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>H08・H09（母集団: 宛先チームの現役メンバー ∪ 直属メンバー、重複排除、キーセット）
 *       → {@link #単一シャードの全ページ走査は宛先チームと直属メンバーの和集合を重複なく昇順で返す()}</li>
 *   <li>H08（宛先外・退会者・他組織は返さない）→ {@link #宛先外と退会者と他組織は返さない()}</li>
 *   <li>H22（応援者トグル）→ {@link #応援者トグルがfalseなら純SUPPORTERを返さずtrueなら返す()}</li>
 *   <li>H10・H33（配信時点でも加盟 ACTIVE を要求）→ {@link #配信時点で加盟していないチームのメンバーは返さない()}</li>
 *   <li>H10・H33（配信時点でアーカイブ済み・論理削除済みのチームを除く。ACTIVE の加盟行が残っていても）
 *       → {@link #配信時点でアーカイブ済みか論理削除済みのチームのメンバーは配信にも総数にも入らない()}</li>
 *   <li>宛先チームが0件の見出し（直属メンバーだけに届く告知）→ {@link #宛先チームが0件の見出しなら直属メンバーだけを返す()}</li>
 *   <li>H32b（シャードの和が全体と一致し重複しない）→ {@link #シャード分割した各シャードの和は全体と一致し重複しない()}</li>
 *   <li>H32c（scope_ref が UUID でない・見出し行が無いと例外）→ {@link #scope_refがUUIDでなければ例外を投げ空集合で終わらない()} ／
 *       {@link #見出し行が無ければ例外を投げ空集合で終わらない()} ／ {@link #見出し行の無いジョブはWorkerがDONEにせず失敗として残す()}</li>
 *   <li>配線: {@link #レジストリに登録され列長に収まる()}</li>
 * </ul>
 */
@DisplayName("F01.2.1 6-E 受信者ソース ORGANIZATION_TEAMS 試練（AC-H08〜H10・H22・H32b・H32c・H33）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrgTeamsFanoutRecipientSourceIT extends AbstractMySqlIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(948_000_000L);

    @Autowired
    private OrgTeamsFanoutRecipientSource source;
    @Autowired
    private FanoutRecipientSourceRegistry registry;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TeamOrgMembershipRepository membershipRepository;
    @Autowired
    private NotificationFanoutAudienceRepository audienceRepository;
    @Autowired
    private NotificationFanoutAudienceTeamRepository audienceTeamRepository;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    /** 1シナリオぶんのフィクスチャ。 */
    private static final class Fx {
        long orgId;
        long otherOrgId;
        long teamA;
        long teamB;
        long teamC;
        UUID audienceId;
        /** TA の現役メンバー。 */
        final List<Long> aMembers = new ArrayList<>();
        /** TB の現役メンバー。 */
        final List<Long> bMembers = new ArrayList<>();
        /** 組織の直属メンバー（応援者でない）。 */
        final List<Long> directs = new ArrayList<>();
        /** TA と TB の両方に属する。 */
        long both;
        /** 宛先外の TC のメンバー。 */
        long cMember;
        /** TA を退会済み。 */
        long leftA;
        /** 組織 Y の直属メンバー。 */
        long otherOrgMember;
        /** TB の純 SUPPORTER。 */
        long teamSupporter;
        /** 組織の純 SUPPORTER。 */
        long orgSupporter;

        Set<Long> expectedAll() {
            Set<Long> all = new TreeSet<>();
            all.addAll(aMembers);
            all.addAll(bMembers);
            all.addAll(directs);
            return all;
        }
    }

    private Fx fixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Fx f = new Fx();
            f.orgId = org().getId();
            f.otherOrgId = org().getId();
            f.teamA = team("TA").getId();
            f.teamB = team("TB").getId();
            f.teamC = team("TC").getId();
            affiliate(f.teamA, f.orgId);
            affiliate(f.teamB, f.orgId);
            affiliate(f.teamC, f.orgId);

            for (int i = 0; i < 14; i++) {
                f.aMembers.add(user(ScopeType.TEAM, f.teamA, RoleKind.MEMBER));
            }
            for (int i = 0; i < 14; i++) {
                f.bMembers.add(user(ScopeType.TEAM, f.teamB, RoleKind.MEMBER));
            }
            f.both = user(ScopeType.TEAM, f.teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, f.both, ScopeType.TEAM, f.teamB, RoleKind.MEMBER);
            f.aMembers.add(f.both);
            f.bMembers.add(f.both);
            for (int i = 0; i < 6; i++) {
                f.directs.add(user(ScopeType.ORGANIZATION, f.orgId, RoleKind.MEMBER));
            }
            // TA のメンバーでもあり直属メンバーでもある人（重複排除の対象）
            long dual = user(ScopeType.TEAM, f.teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, dual, ScopeType.ORGANIZATION, f.orgId, RoleKind.MEMBER);
            f.aMembers.add(dual);
            f.directs.add(dual);

            f.cMember = user(ScopeType.TEAM, f.teamC, RoleKind.MEMBER);
            f.leftA = user(ScopeType.TEAM, f.teamA, RoleKind.MEMBER);
            em.flush();
            em.createNativeQuery("UPDATE memberships SET left_at = DATE_ADD(joined_at, INTERVAL 1 SECOND), "
                            + "leave_reason = 'SELF' WHERE user_id = :u AND scope_type = 'TEAM' AND scope_id = :s")
                    .setParameter("u", f.leftA).setParameter("s", f.teamA).executeUpdate();
            f.otherOrgMember = user(ScopeType.ORGANIZATION, f.otherOrgId, RoleKind.MEMBER);
            f.teamSupporter = user(ScopeType.TEAM, f.teamB, RoleKind.SUPPORTER);
            f.orgSupporter = user(ScopeType.ORGANIZATION, f.orgId, RoleKind.SUPPORTER);

            f.audienceId = audience(f.orgId, f.teamA, f.teamB);
            return f;
        });
    }

    private OrganizationEntity org() {
        return organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .name("6E源泉組織" + System.nanoTime())
                .slug("os6e-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.incrementAndGet())
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .teamGroupsEnabled(true)
                .build());
    }

    private TeamEntity team(String name) {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .slug("os6e-t-" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build());
    }

    private void affiliate(long teamId, long orgId) {
        membershipRepository.saveAndFlush(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .build());
    }

    private long user(ScopeType scopeType, long scopeId, RoleKind kind) {
        long id = SEQ.incrementAndGet();
        MembershipTestHelper.insertActiveUser(em, id);
        MembershipTestHelper.insertMembership(em, id, scopeType, scopeId, kind);
        return id;
    }

    /** 宛先集合（見出し＋宛先チーム）を作り、その audience_snapshot_id を返す。 */
    private UUID audience(long orgId, long... teamIds) {
        UUID id = UUID.randomUUID();
        NotificationFanoutAudienceEntity header = NotificationFanoutAudienceEntity.builder()
                .organizationId(orgId).build();
        header.setId(id);
        audienceRepository.saveAndFlush(header);
        for (long teamId : teamIds) {
            audienceTeamRepository.saveAndFlush(NotificationFanoutAudienceTeamEntity.builder()
                    .audienceSnapshotId(id).teamId(teamId).build());
        }
        return id;
    }

    private List<Long> scanAll(String scopeRef, boolean includeSupporters, int shardIndex, int shardCount, int limit) {
        List<Long> all = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<FanoutRecipient> page = source.nextPage(
                    new FanoutPageRequest(scopeRef, cursor, limit, includeSupporters, shardIndex, shardCount));
            if (page.isEmpty()) {
                return all;
            }
            page.forEach(r -> all.add(r.userId()));
            cursor = page.get(page.size() - 1).userId();
        }
    }

    // =====================================================================
    // 母集団（H08・H09・H10・H22・H33）
    // =====================================================================

    @Test
    @DisplayName("H08/H09: 全ページ走査は宛先チームの現役メンバー ∪ 直属メンバーを、重複なく user_id 昇順で返す")
    void 単一シャードの全ページ走査は宛先チームと直属メンバーの和集合を重複なく昇順で返す() {
        Fx f = fixture();

        List<Long> scanned = scanAll(f.audienceId.toString(), false, 0, 1, 7);

        assertThat(scanned).as("重複なし").doesNotHaveDuplicates();
        assertThat(scanned).as("user_id 昇順（キーセット）").isSorted();
        assertThat(scanned).as("TA ∪ TB ∪ 直属。複数所属のユーザーも1回だけ")
                .containsExactlyElementsOf(f.expectedAll());
    }

    @Test
    @DisplayName("H08: 宛先外チーム(TC)・退会済み・他組織の直属メンバーは返さない")
    void 宛先外と退会者と他組織は返さない() {
        Fx f = fixture();

        List<Long> scanned = scanAll(f.audienceId.toString(), true, 0, 1, 100);

        assertThat(scanned).doesNotContain(f.cMember, f.leftA, f.otherOrgMember);
    }

    @Test
    @DisplayName("H22: includeSupporters=false は純 SUPPORTER（チーム・組織）を返さず、true なら返す")
    void 応援者トグルがfalseなら純SUPPORTERを返さずtrueなら返す() {
        Fx f = fixture();

        List<Long> without = scanAll(f.audienceId.toString(), false, 0, 1, 100);
        List<Long> with = scanAll(f.audienceId.toString(), true, 0, 1, 100);

        assertThat(without).doesNotContain(f.teamSupporter, f.orgSupporter);
        assertThat(with).contains(f.teamSupporter, f.orgSupporter);
        assertThat(with).containsAll(f.expectedAll());
    }

    @Test
    @DisplayName("H10/H33: 配信時点で組織に加盟していないチームのメンバーは、宛先集合に残っていても返さない（直属は返す）")
    void 配信時点で加盟していないチームのメンバーは返さない() {
        Fx f = fixture();
        jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?", f.teamA, f.orgId);

        List<Long> scanned = scanAll(f.audienceId.toString(), false, 0, 1, 100);

        Set<Long> aOnly = new HashSet<>(f.aMembers);
        aOnly.removeAll(f.directs);
        aOnly.removeAll(f.bMembers);
        assertThat(scanned).as("TA だけに属する人は返さない").doesNotContainAnyElementsOf(aOnly);
        assertThat(scanned).as("TA を離れた後も、TB・直属としての所属は残る")
                .containsAll(f.bMembers).containsAll(f.directs);
    }

    @Test
    @DisplayName("H10/H33: 送信後にアーカイブ・論理削除されたチームのメンバーは、ACTIVE の加盟行が残っていても配信にも総数にも入らない（直属は返す）")
    void 配信時点でアーカイブ済みか論理削除済みのチームのメンバーは配信にも総数にも入らない() {
        Fx f = fixture();
        // 宛先集合（送信時スナップショット）を作った後に、TA をアーカイブ・TB を論理削除する。
        // §4.5・§6.6 によりアーカイブしても加盟行は ACTIVE のまま残る（ここでも加盟行には触れない）。
        jdbc.update("UPDATE teams SET archived_at = UTC_TIMESTAMP() WHERE id = ?", f.teamA);
        jdbc.update("UPDATE teams SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", f.teamB);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE organization_id = ? AND team_id IN (?, ?) AND status = 'ACTIVE'",
                Integer.class, f.orgId, f.teamA, f.teamB))
                .as("前提: 加盟行は ACTIVE のまま残っている").isEqualTo(2);
        String ref = f.audienceId.toString();

        List<Long> scanned = scanAll(ref, false, 0, 1, 7);
        List<Long> scannedWithSupporters = scanAll(ref, true, 0, 1, 7);

        Set<Long> teamOnly = new HashSet<>(f.aMembers);
        teamOnly.addAll(f.bMembers);
        teamOnly.removeAll(f.directs);
        assertThat(scanned).as("アーカイブ済み TA・論理削除済み TB だけに属する人は返さない")
                .doesNotContainAnyElementsOf(teamOnly);
        assertThat(scannedWithSupporters).as("応援者込みでも TB の SUPPORTER は返さない")
                .doesNotContainAnyElementsOf(teamOnly).doesNotContain(f.teamSupporter);
        assertThat(scanned).as("直属メンバーは引き続き返す（TA との兼任者も直属として1回）")
                .containsExactlyElementsOf(new TreeSet<>(f.directs));
        assertThat(source.countRecipients(ref, false)).as("総数（自動シャード数の算出）も配信と同じ判定で数える")
                .isEqualTo(scanned.size());
        assertThat(source.countRecipients(ref, true)).as("応援者込みの総数も配信と一致")
                .isEqualTo(scannedWithSupporters.size());
    }

    @Test
    @DisplayName("宛先チームが0件の見出しは、直属メンバーだけを返す（0件でも例外にしない）")
    void 宛先チームが0件の見出しなら直属メンバーだけを返す() {
        Fx f = fixture();
        UUID directOnly = new TransactionTemplate(transactionManager).execute(status -> audience(f.orgId));

        List<Long> scanned = scanAll(directOnly.toString(), false, 0, 1, 100);

        assertThat(scanned).containsExactlyElementsOf(new TreeSet<>(f.directs));
    }

    // =====================================================================
    // H32b シャード
    // =====================================================================

    @Test
    @DisplayName("H32b: shardCount=3 のとき各シャードの和が全体と一致し、重複せず、各ユーザーは user_id % 3 のシャードにだけ現れる。countRecipients も母集団と一致")
    void シャード分割した各シャードの和は全体と一致し重複しない() {
        Fx f = fixture();
        String ref = f.audienceId.toString();
        Set<Long> whole = f.expectedAll();

        List<Long> union = new ArrayList<>();
        for (int shard = 0; shard < 3; shard++) {
            List<Long> part = scanAll(ref, false, shard, 3, 5);
            for (long userId : part) {
                assertThat(userId % 3).as("userId=" + userId + " は shard " + shard + " の区画").isEqualTo(shard);
            }
            union.addAll(part);
        }

        assertThat(union).as("シャード間で重複しない").doesNotHaveDuplicates();
        assertThat(new TreeSet<>(union)).as("各シャードの和は全体と一致する").isEqualTo(whole);
        assertThat(source.countRecipients(ref, false)).as("自動シャード数の算出に使う総数は母集団と一致")
                .isEqualTo(whole.size());
    }

    // =====================================================================
    // H32c 異常系
    // =====================================================================

    @Test
    @DisplayName("H32c: scope_ref を UUID として解釈できなければ、空集合で完了せず例外を投げる")
    void scope_refがUUIDでなければ例外を投げ空集合で終わらない() {
        assertThatThrownBy(() -> source.nextPage(new FanoutPageRequest("not-a-uuid", 0L, 100, false, 0, 1)))
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    @DisplayName("H32c: 見出し行（notification_fanout_audiences）が無ければ、空集合で完了せず例外を投げる")
    void 見出し行が無ければ例外を投げ空集合で終わらない() {
        String missing = UUID.randomUUID().toString();

        assertThatThrownBy(() -> source.nextPage(new FanoutPageRequest(missing, 0L, 100, false, 0, 1)))
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    @DisplayName("H32c: 見出し行の無い ORGANIZATION_TEAMS ジョブを Worker に処理させても DONE にならず、失敗として last_error が残る")
    void 見出し行の無いジョブはWorkerがDONEにせず失敗として残す() {
        Fx f = fixture();
        String missing = UUID.randomUUID().toString();
        UUID jobId = new TransactionTemplate(transactionManager).execute(status ->
                jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                        OrgTeamsFanoutRecipientSource.SCOPE_TYPE, missing, "F0121_IT_H32C_" + UUID.randomUUID(),
                        UUID.randomUUID(), f.orgId, NotificationPriority.NORMAL, null,
                        "ANNOUNCEMENT_FEED", 1L, "/organizations/h32c/surveys/1", false,
                        FanoutMessageKind.SURVEY_PUBLISHED, List.of("H32c"),
                        FanoutEnqueueCommand.ShardMode.AUTO)).jobId());

        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).as("空集合で DONE にしない").isNotEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(job.getLastError()).as("握りつぶさず last_error に残す")
                .containsAnyOf("IllegalStateException", "IllegalArgumentException");
    }

    // =====================================================================
    // 配線
    // =====================================================================

    @Test
    @DisplayName("ORGANIZATION_TEAMS がレジストリに登録され、scope_type は VARCHAR(20) に収まる")
    void レジストリに登録され列長に収まる() {
        assertThat(registry.resolve("ORGANIZATION_TEAMS")).containsSame(source);
        assertThat(OrgTeamsFanoutRecipientSource.SCOPE_TYPE).isEqualTo("ORGANIZATION_TEAMS")
                .hasSizeLessThanOrEqualTo(20);
    }
}
