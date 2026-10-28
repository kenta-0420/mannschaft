package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import com.mannschaft.app.notification.outbox.NotificationOutboxRelay;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamAffiliationAuditRecorder;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionService;
import com.mannschaft.app.team.service.TeamAffiliationNotifier;
import com.mannschaft.app.team.service.TeamAffiliationOrganizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 2-C — コミットを伴う検証（通知の配信・並行競合）の統合テスト（試練）。
 *
 * <p>{@link TeamOrgInviteIT} は {@code @Transactional} でロールバックするため、トランザクションをまたぐ
 * 競合・ロック待ち・Worker による配信は検証できない。本クラスは<b>テストメソッドにトランザクションを張らず</b>、
 * フィクスチャはトランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。</p>
 *
 * <h2>検証すること</h2>
 * <ul>
 *   <li>AC-D01・G117d・P07: 招待の通知は、チームの加盟操作者（TA・TG）にだけ届き、TM・TD・組織 ADMIN には届かない</li>
 *   <li>AC-G117e: 承諾の通知は、組織 ADMIN 全員にだけ届く</li>
 *   <li>並行: 同じ (チーム, 組織) への招待と申請が同時に来ても、行は1件だけ（チーム行 → 組織行の固定順ロック）</li>
 *   <li>並行: 承諾と取消が同時に来ても、ちょうど一方だけが成功し、判定表どおりの応答になる</li>
 * </ul>
 *
 * <h2>通知 outbox（docs/architecture/notification_outbox.md・陣立て書 2026-10-10 P1）</h2>
 * <p>招待・承諾の通知は team の outbox に書き、通知ドメインの relay が取り込んで fan-out ジョブを作る。
 * 試験プロファイルでは起こしを止めてあるので、ジョブを見る前に {@link NotificationOutboxRelay#drainAll()} を挟む（OB21）。</p>
 * <ul>
 *   <li>OB13a: 招待のコミット後の読み直しで組織の閉鎖を見つけたら、招待は消え、outbox もジョブも作られない</li>
 *   <li>OB13b: 正常な招待は、読み直しの後に outbox へ1行。relay が失敗しても招待と監査は残る（旧 :189 の置換）</li>
 *   <li>OB13c: 読み直しから outbox の書き込みまでの間に招待が取り消されたら、何も書かない</li>
 *   <li>OB14: 承諾の通知は承諾の tx と同時に outbox に確定する（outbox の書き込みが失敗すれば承諾も巻き戻る）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-C 招待の通知の配信・並行競合（コミットを伴う検証）")
class TeamOrgInviteCommittedIT extends TeamOrgInviteItSupport {

    private static final Logger log = LoggerFactory.getLogger(TeamOrgInviteCommittedIT.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationFanoutJobRepository jobRepository;

    @Autowired
    private NotificationFanoutWorker worker;

    @Autowired
    private NotificationOutboxRelay relay;

    /** 2-D: イベント（OrganizationArchivedEvent）を出す本物の組織のアーカイブを差し込むために使う。 */
    @Autowired
    private com.mannschaft.app.organization.service.OrganizationService organizationService;

    /** relay の取り込みの失敗を起こすために差し替える（既定は実物を呼ぶ）。 */
    @MockitoSpyBean
    private NotificationOutboxIngestService ingestSpy;

    /** outbox の書き込みの失敗を起こすために差し替える（既定は実物を呼ぶ）。 */
    @MockitoSpyBean
    private TeamAffiliationNotifier notifierSpy;

    /** 招待のコミット後の読み直しの前後に、組織の閉鎖・招待の取消を差し込むために差し替える（既定は実物を呼ぶ）。 */
    @MockitoSpyBean
    private TeamAffiliationOrganizationPort organizationPortSpy;

    @Autowired
    private TeamOrgMembershipRepository membershipRepository;

    @MockitoSpyBean
    private TeamAffiliationAuditRecorder auditSpy;

    @MockitoSpyBean
    private TeamOrgAffiliationRestrictionService restrictionSpy;

    private final List<RaceFixture> raceFixtures = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
        for (RaceFixture fixture : raceFixtures) {
            assertThat(membershipCount(fixture.team().id(), fixture.org().id())).isZero();
            assertThat(outboxCountByOrganization(fixture.org().id())).isZero();
            assertThat(raceEffects(fixture)).isEqualTo(new RaceEffects(0, 0, 0, 0));
            assertThat(countRaceRows("SELECT COUNT(*) FROM teams WHERE id = ?", fixture.team().id())).isZero();
            assertThat(countRaceRows("SELECT COUNT(*) FROM organizations WHERE id = ?", fixture.org().id())).isZero();
            assertThat(countRaceRows("SELECT COUNT(*) FROM users WHERE id IN (?, ?)", fixture.orgAdmin(), fixture.teamAdmin())).isZero();
        }
    }

    /**
     * スタブを置く先（AOP プロキシの内側の spy）。プロキシ越しに {@code when(spy).method(...)} を呼ぶと
     * スタブの登録の呼び出しにも tx の境界が掛かる（{@code MANDATORY} は tx の外で落ちて未完了のスタブが後続を汚染し、
     * {@code readOnly} は読み取り専用の tx を無駄に張る）ので、内側の spy に直接置く。
     */
    private TeamAffiliationNotifier notifierTarget() {
        return AopTestUtils.getUltimateTargetObject(notifierSpy);
    }

    private TeamAffiliationOrganizationPort organizationPortTarget() {
        return AopTestUtils.getUltimateTargetObject(organizationPortSpy);
    }

    /**
     * 「別の操作がコミットした」状況を、別スレッド（= 別のコネクション・tx 文脈なし）の自動コミットで作る。
     *
     * <p>差し込みの呼び出し元は組織ドメインの読み取り専用 tx（{@code recheckAffiliationStateLocked} の tx。2-D 以前は {@code findAffiliationState} の {@code readOnly}）の中なので、
     * そこで書くと {@code Connection is read-only} で落ちる。同じ tx で先に読むと REPEATABLE READ のスナップショットが
     * 確定して後のコミットが見えなくなるため、存在の確認も含めて別スレッドで行う。</p>
     */
    private void commitByAnotherSession(Runnable action) {
        CompletableFuture.runAsync(action).join();
    }

    // =====================================================================
    // AC-D01・G117d・P07 招待の通知はチームの加盟操作者にだけ届く
    // =====================================================================

    @Test
    @DisplayName("AC-G117d・P07 招待の通知を Worker が配信すると、TA・TG にだけ ?view=invites 付きで1件ずつ届き、TM・TD・XA には届かない")
    void 招待の通知はチームの加盟操作者にだけ届く() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            long td = newUser();
            long tm = newUser();
            long tg = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            makeTeamDeputy(td, team.id());
            makeTeamMember(tm, team.id());
            makeTeamMember(tg, team.id());
            grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
            return new long[]{xa, ta, td, tm, tg};
        });

        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);
        relay.drainAll(); // OB21: outbox → fan-out ジョブ

        UUID jobId = jobIdOf(invited.id(), "TEAM_ORG_INVITE_RECEIVED");
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getSourceType()).isEqualTo("TEAM_ORG_MEMBERSHIP");
        Map<String, Object> jobRow = jobRow(jobId);
        assertThat(jobRow.get("scope_type")).as("受信者はチームの加盟操作者").isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(jobRow.get("scope_ref")).as("受信者を引く対象はチーム").isEqualTo(String.valueOf(team.id()));
        assertThat(((Number) jobRow.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(((Number) jobRow.get("source_id")).longValue()).isEqualTo(invited.id());
        worker.processOne(job);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);

        List<Map<String, Object>> rows = notificationRows("TEAM_ORG_INVITE_RECEIVED", users);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("TA と TG にだけ1件ずつ届く（TM・TD・XA には届かない）")
                .containsExactlyInAnyOrder(users[1], users[4]);
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/teams/" + team.slug() + "/affiliations?view=invites");
    }

    // =====================================================================
    // AC-G117e 承諾の通知は組織 ADMIN にだけ届く
    // =====================================================================

    @Test
    @DisplayName("AC-G117e 承諾の通知を Worker が配信すると、組織 ADMIN 全員にだけ member-teams へのリンク付きで届く")
    void 承諾の通知は組織ADMINにだけ届く() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa1 = newUser();
            long xa2 = newUser();
            long xd = newUser();
            long ta = newUser();
            makeOrgAdmin(xa1, org.id());
            makeOrgAdmin(xa2, org.id());
            makeOrgDeputy(xd, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa1, xa2, xd, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);

        Result accepted = perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", team.slug(), invited.id())
                .with(user(String.valueOf(users[3]))));
        assertThat(accepted.status()).isEqualTo(200);
        relay.drainAll(); // OB21: outbox → fan-out ジョブ

        UUID jobId = jobIdOf(invited.id(), "TEAM_ORG_INVITE_ACCEPTED");
        Map<String, Object> jobRow = jobRow(jobId);
        assertThat(jobRow.get("scope_type")).as("受信者は組織 ADMIN").isEqualTo("ORGANIZATION_ADMINS");
        assertThat(jobRow.get("scope_ref")).as("受信者を引く対象は組織").isEqualTo(String.valueOf(org.id()));
        assertThat(((Number) jobRow.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(((Number) jobRow.get("source_id")).longValue()).isEqualTo(invited.id());
        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        List<Map<String, Object>> rows = notificationRows("TEAM_ORG_INVITE_ACCEPTED", users);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("組織 ADMIN の2名にだけ届く（組織 DEPUTY・承諾した TA には届かない）")
                .containsExactlyInAnyOrder(users[0], users[1]);
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/organizations/" + org.slug() + "/member-teams");
    }

    // =====================================================================
    // OB13b 招待の通知は読み直しの後に outbox へ。relay の失敗で招待と監査は巻き戻らない（旧 :189 の置換）
    // =====================================================================

    @Test
    @DisplayName("OB13b 正常な招待は読み直しの後に outbox へ PENDING 1行（relay 前はジョブなし）。relay の取り込みが失敗しても、招待の行と TEAM_ORG_INVITE_SENT の監査は残り、outbox は再試行待ち")
    void ob13b_招待の通知はoutboxに書かれrelayが失敗しても招待と監査は残る() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long xa = inTx(() -> {
            seedAffiliationPermission();
            long id = newUser();
            makeOrgAdmin(id, org.id());
            return id;
        });

        Result invited = invite(xa, org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_INVITE_RECEIVED, invited.id());
        assertThat(outboxStatus(key)).as("招待の通知は outbox に書かれている").isEqualTo("PENDING");
        assertThat(jobCountByKey(key)).as("relay の前はジョブが無い").isZero();

        doThrow(new IllegalStateException("取り込みの失敗（テスト）")).when(ingestSpy).ingest(any());
        relay.drainAll();

        assertThat(membershipCount(team.id(), org.id())).as("招待の行は巻き戻らない").isEqualTo(1);
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'TEAM_ORG_INVITE_SENT' AND team_id = ?",
                Long.class, team.id());
        assertThat(audits).as("監査は relay の失敗と無関係に残る").isEqualTo(1L);
        assertThat(outboxStatus(key)).as("取り込みの失敗は outbox 側で再試行待ちになる").isEqualTo("PENDING");
        assertThat(jobCountByKey(key)).isZero();
    }

    // =====================================================================
    // OB13a 読み直しで組織の閉鎖を見つけたら、招待も通知も残さない
    // =====================================================================

    @Test
    @DisplayName("OB13a 招待のコミット後の読み直しで組織のアーカイブを見つけると、招待は取り下げられ、outbox も fan-out ジョブも作られない")
    void ob13a_読み直しで閉鎖を見つけたら招待もoutboxも残さない() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long xa = inTx(() -> {
            seedAffiliationPermission();
            long id = newUser();
            makeOrgAdmin(id, org.id());
            return id;
        });
        // 招待の行がコミットされた後の読み直しの直前に、組織をアーカイブする（別の操作のコミットとして差し込む）
        Injection injection = new Injection();
        doAnswer(invocation -> {
            commitByAnotherSession(() -> {
                String row = membershipRowState(team.id(), org.id());
                if (row != null) {
                    injection.observedRows.add(row);
                    injection.affectedRows.add(jdbc.update(
                            "UPDATE organizations SET archived_at = UTC_TIMESTAMP() WHERE id = ?", org.id()));
                }
            });
            return invocation.callRealMethod();
        }).when(organizationPortTarget()).recheckAffiliationStateLocked(any());

        Result invited = invite(xa, org.slug(), team.slug());
        relay.drainAll();

        // 差し込みが「招待の行（PENDING / ORG_INVITE）ができた後」にちょうど1回成立したこと（招待前の 403・404 で
        // 終わって差し込みが走らないまま通る偽 green を塞ぐ）
        assertThat(injection.observedRows).as("差し込みの時点の招待の行").containsExactly("PENDING/ORG_INVITE");
        assertThat(injection.affectedRows).as("アーカイブの UPDATE の影響行数").containsExactly(1);
        assertThat(invited.status()).isNotEqualTo(201);
        assertThat(invited.errorCode()).as("読み直しでアーカイブを見つけた").isEqualTo("ORG_003");
        assertThat(membershipCount(team.id(), org.id())).as("作った招待は取り下げられる").isZero();
        assertThat(outboxCountByOrganization(org.id())).as("outbox に書かない").isZero();
        assertThat(jobCountByOrganization(org.id())).as("fan-out ジョブも作られない").isZero();
    }

    @Test
    @DisplayName("OB13a（2-D・イベント経由のアーカイブ）: 招待のコミット後・読み直しの前に、組織のアーカイブ API がコミットされ、"
            + "そのリスナー（OrganizationArchivedEvent）の片付けが招待の取り下げより先に招待の行を消しても、"
            + "チームの知らない招待なので取消の通知を予約しない。招待は ORG_003 で拒否され、outbox もジョブも0件")
    void ob13a_イベント経由のアーカイブの片付けが先に走っても取消を通知しない() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long xa = inTx(() -> {
            seedAffiliationPermission();
            long id = newUser();
            makeOrgAdmin(id, org.id());
            return id;
        });
        // 招待の行がコミットされた後の読み直しの直前に、本物の組織のアーカイブ（イベントを出す）をコミットし、
        // その AFTER_COMMIT のリスナーが招待の行を消し終えるまで待つ（招待の側の取り下げより片付けが先になる順序）
        Injection injection = new Injection();
        doAnswer(invocation -> {
            commitByAnotherSession(() -> {
                String row = membershipRowState(team.id(), org.id());
                if (row != null) {
                    injection.observedRows.add(row);
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                            status -> organizationService.archiveOrganization(org.id()));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (membershipCount(team.id(), org.id()) != 0 && System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    injection.affectedRows.add((int) membershipCount(team.id(), org.id()));
                }
            });
            return invocation.callRealMethod();
        }).when(organizationPortTarget()).recheckAffiliationStateLocked(any());

        Result invited = invite(xa, org.slug(), team.slug());
        relay.drainAll();

        assertThat(injection.observedRows).as("差し込みの時点の招待の行").containsExactly("PENDING/ORG_INVITE");
        assertThat(injection.affectedRows).as("リスナーの片付けが招待の行を消し終えていた（残りの行数）").containsExactly(0);
        assertThat(invited.status()).isNotEqualTo(201);
        assertThat(invited.errorCode()).as("読み直しでアーカイブを見つけた").isEqualTo("ORG_003");
        assertThat(membershipCount(team.id(), org.id())).isZero();
        assertThat(outboxCountByOrganization(org.id()))
                .as("招待の受信通知も、チームの知らない招待の取消も outbox に書かない").isZero();
        assertThat(jobCountByOrganization(org.id())).as("fan-out ジョブも作られない").isZero();
    }

    // =====================================================================
    // OB13c 読み直しから書き込みまでの間の取消
    // =====================================================================

    @Test
    @DisplayName("OB13c 読み直しから outbox の書き込みまでの間に招待が取り消されていたら、outbox にも fan-out ジョブにも何も書かない")
    void ob13c_読み直しの後に取り消された招待の通知は書かない() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long xa = inTx(() -> {
            seedAffiliationPermission();
            long id = newUser();
            makeOrgAdmin(id, org.id());
            return id;
        });
        // 読み直し（組織は生存）を通過した直後に、別の操作が招待を取り消してコミットした状況を作る
        Injection injection = new Injection();
        doAnswer(invocation -> {
            Object state = invocation.callRealMethod();
            commitByAnotherSession(() -> {
                String row = membershipRowState(team.id(), org.id());
                if (row != null) {
                    injection.observedRows.add(row);
                    injection.affectedRows.add(jdbc.update(
                            "DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                            team.id(), org.id()));
                }
            });
            return state;
        }).when(organizationPortTarget()).recheckAffiliationStateLocked(any());

        Result invited = invite(xa, org.slug(), team.slug());
        relay.drainAll();

        // 差し込みが「招待の行（PENDING / ORG_INVITE）ができ、読み直しを通過した後」にちょうど1回成立したこと
        assertThat(injection.observedRows).as("差し込みの時点の招待の行").containsExactly("PENDING/ORG_INVITE");
        assertThat(injection.affectedRows).as("取消の DELETE の影響行数").containsExactly(1);
        // 現実装: 読み直し（組織は生存）を通過しているので招待作成の応答は 201。通知の予約だけが取消を見て書かない
        assertThat(invited.status()).isEqualTo(201);
        assertThat(membershipCount(team.id(), org.id())).as("加盟行は取消で消えたまま").isZero();
        assertThat(outboxCountByOrganization(org.id()))
                .as("招待の行が PENDING のまま残っていることを FOR UPDATE で確かめてから書く").isZero();
        assertThat(jobCountByOrganization(org.id())).isZero();
    }

    // =====================================================================
    // OB14 承諾の通知は承諾の tx と同時に確定
    // =====================================================================

    @Test
    @DisplayName("OB14 承諾がコミットされた時点で TEAM_ORG_INVITE_ACCEPTED の outbox が PENDING で1行あり（relay 前はジョブなし）、drain でジョブが1件できる")
    void ob14_承諾の通知は承諾と同時にoutboxに確定する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);

        Result accepted = perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", team.slug(), invited.id())
                .with(user(String.valueOf(users[1]))));
        assertThat(accepted.status()).isEqualTo(200);

        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_INVITE_ACCEPTED, invited.id());
        assertThat(outboxStatus(key)).isEqualTo("PENDING");
        assertThat(jobCountByKey(key)).isZero();
        relay.drainAll();
        assertThat(jobCountByKey(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("OB14 承諾の outbox の書き込みが失敗すると、承諾も巻き戻り、招待は PENDING のまま残る")
    void ob14_承諾のoutbox書き込みが失敗すると承諾も巻き戻る() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);
        doThrow(new IllegalStateException("outbox の書き込み失敗（テスト）")).when(notifierTarget())
                .enqueue(argThat(n -> n != null && n.notificationType() == NotificationType.TEAM_ORG_INVITE_ACCEPTED));

        int status;
        try {
            status = perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", team.slug(), invited.id())
                    .with(user(String.valueOf(users[1])))).status();
        } catch (Exception propagated) {
            status = 500;
        }

        assertThat(status).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM team_org_memberships WHERE id = ?", String.class,
                invited.id())).as("承諾は巻き戻り、招待のまま").isEqualTo("PENDING");
        assertThat(outboxStatus(idempotencyKeyOf(NotificationType.TEAM_ORG_INVITE_ACCEPTED, invited.id()))).isNull();
        assertThat(countRaceRows("SELECT COUNT(*) FROM audit_logs WHERE event_type = 'TEAM_ORG_INVITE_ACCEPTED' AND team_id = ? AND organization_id = ?",
                team.id(), org.id())).isZero();
    }

    // =====================================================================
    // 招待と申請の並行競合（チーム行 → 組織行の固定順ロック）
    // =====================================================================

    @Test
    @DisplayName("並行: 同じ (チーム, 組織) への招待と申請を同時に送ると、ちょうど一方だけが 201、もう一方は 409 TEAM_066 で、行は1件だけ")
    void 招待と申請の同時実行は一方だけ成功する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });

        for (int round = 0; round < 3; round++) {
            List<Result> results = runConcurrently(List.of(
                    () -> invite(users[0], org.slug(), team.slug()),
                    () -> perform(post("/api/v1/teams/{teamSlug}/org-applications", team.slug())
                            .with(user(String.valueOf(users[1])))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("organizationSlug", org.slug()))))));

            assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
            assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().errorCode())
                    .isEqualTo("TEAM_066");
            assertThat(membershipCount(team.id(), org.id())).as("行は1件だけ").isEqualTo(1);
            jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                    team.id(), org.id());
        }
    }

    // =====================================================================
    // 承諾と取消の並行競合（§6.4 の判定表）
    // =====================================================================

    /** 現契約のcharacterization。2順序のgreenだけでは元500の原因・根治の証拠にしない。 */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("1822-01/06/07 承諾の実ロック先行で200、取消409 TEAM_071、承諾副作用のみ1件")
    void 承諾ロック先行では取消が409で成功副作用は一度だけ() throws Exception {
        runInviteRace(newRaceFixture(), TeamOrgInviteRaceProbe.Operation.ACCEPT);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("1822-02/06/07 取消の実ロック先行で204、承諾404 TEAM_070、取消副作用のみ1件")
    void 取消ロック先行では承諾が404で成功副作用は一度だけ() throws Exception {
        runInviteRace(newRaceFixture(), TeamOrgInviteRaceProbe.Operation.CANCEL);
    }

    @Test
    @Timeout(value = 9, unit = TimeUnit.MINUTES)
    @DisplayName("1822-06/07 同時startを10個のfresh fixtureで各1回観測し500・二重成功・部分副作用を拒否")
    void 承諾と取消の同時実行は一方だけ成功する() throws Exception {
        for (int round = 0; round < 10; round++) {
            runInviteRace(newRaceFixture(), null);
        }
    }

    @Test
    @DisplayName("1822-04 匿名の承諾・取消は実Securityフィルタで401、招待と副作用は不変")
    void 匿名の承諾と取消は401で招待を変更しない() throws Exception {
        RaceFixture fixture = newRaceFixture();

        Result accept = perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", fixture.team().slug(), fixture.membershipId()));
        Result cancel = perform(delete("/api/v1/organizations/{slug}/team-invites/{teamSlug}", fixture.org().slug(), fixture.team().slug()));

        assertThat(accept.status()).isEqualTo(401);
        assertThat(cancel.status()).isEqualTo(401);
        assertThat(membershipRowState(fixture.team().id(), fixture.org().id())).isEqualTo("PENDING/ORG_INVITE");
        assertThat(raceEffects(fixture)).isEqualTo(new RaceEffects(0, 0, 0, 0));
    }

    @Test
    @DisplayName("1822-05 取消の実DELETE後の制限書込失敗はPENDINGへrollbackし、制限・成功監査0")
    void 取消の制限書込失敗は削除も巻き戻す() throws Exception {
        RaceFixture fixture = newRaceFixture();
        TeamOrgAffiliationRestrictionService target = AopTestUtils.getUltimateTargetObject(restrictionSpy);
        doThrow(new IllegalStateException("取消制限の注入失敗（元競合の原因証明ではない）"))
                .when(target).record(eq(fixture.org().id()), eq(fixture.team().id()), any(), any(), any(), any(), any());

        try (TeamOrgInviteRaceProbe probe = new TeamOrgInviteRaceProbe(membershipRepository,
                fixture.membershipId(), fixture.team().id(), fixture.org().id(), null)) {
            Result result = probe.run(TeamOrgInviteRaceProbe.Operation.CANCEL, () -> raceRequest(cancelRequest(fixture)));

            assertThat(result.status() >= 200 && result.status() <= 299).isFalse();
            assertThat(membershipRowState(fixture.team().id(), fixture.org().id())).isEqualTo("PENDING/ORG_INVITE");
            assertThat(raceEffects(fixture)).isEqualTo(new RaceEffects(0, 0, 0, 0));
            assertThat(probe.diagnostic()).contains("deletePendingInviteByOrganization:rows=1", "CANCEL:tx=ROLLED_BACK");
            probe.assertHarness();
        }
    }

    private record RaceFixture(TeamFx team, OrgFx org, long orgAdmin, long teamAdmin, long membershipId) {
    }

    private record RaceEffects(long acceptedOutbox, long acceptedAudits, long cancelledAudits, long restrictions) {
    }

    private RaceFixture newRaceFixture() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);
        assertThat(membershipCount(team.id(), org.id())).isEqualTo(1);
        assertThat(membershipRowState(team.id(), org.id())).isEqualTo("PENDING/ORG_INVITE");
        RaceFixture fixture = new RaceFixture(team, org, users[0], users[1], invited.id());
        raceFixtures.add(fixture);
        assertThat(raceEffects(fixture)).as("成功副作用の開始値").isEqualTo(new RaceEffects(0, 0, 0, 0));
        return fixture;
    }

    private void runInviteRace(RaceFixture fixture, TeamOrgInviteRaceProbe.Operation winner) throws Exception {
        long started = System.nanoTime();
        try (TeamOrgInviteRaceProbe probe = new TeamOrgInviteRaceProbe(membershipRepository,
                fixture.membershipId(), fixture.team().id(), fixture.org().id(), winner)) {
            observeRaceAudit(fixture, probe);
            List<Result> results = executeInviteRace(fixture, winner, probe);
            // 両Future完了後、テストTXも古いEntityManagerも使わず最終commitを読む。
            String state = membershipRowState(fixture.team().id(), fixture.org().id());
            long rows = membershipCount(fixture.team().id(), fixture.org().id());
            RaceEffects effects = raceEffects(fixture);
            String diagnostic = "HTTP=" + results + ", rows=" + rows + ", state=" + state
                    + ", effects=" + effects + ", " + probe.diagnostic();
            probe.assertHarness();
            Result accept = results.get(0);
            Result cancel = results.get(1);
            boolean accepted = winner == TeamOrgInviteRaceProbe.Operation.ACCEPT || (winner == null && accept.status() == 200);
            assertThat(results.stream().filter(r -> r.status() >= 200 && r.status() < 300).count()).as(diagnostic).isEqualTo(1);
            assertThat(accept.status()).as(diagnostic).isEqualTo(accepted ? 200 : 404);
            assertThat(accept.errorCode()).as(diagnostic).isEqualTo(accepted ? null : "TEAM_070");
            assertThat(cancel.status()).as(diagnostic).isEqualTo(accepted ? 409 : 204);
            assertThat(cancel.errorCode()).as(diagnostic).isEqualTo(accepted ? "TEAM_071" : null);
            assertThat(rows).as(diagnostic).isEqualTo(accepted ? 1 : 0);
            assertThat(state).as(diagnostic).isEqualTo(accepted ? "ACTIVE/ORG_INVITE" : null);
            assertThat(effects).as(diagnostic).isEqualTo(accepted ? new RaceEffects(1, 1, 0, 0) : new RaceEffects(0, 0, 1, 1));
            probe.assertTransactions(accepted);
            log.warn("[invite-race] order={} accept={}/{} cancel={}/{} rows={} effects={} elapsedMs={}",
                    winner == null ? "SIMULTANEOUS" : winner, accept.status(), accept.errorCode(),
                    cancel.status(), cancel.errorCode(), rows, effects,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private List<Result> executeInviteRace(RaceFixture fixture, TeamOrgInviteRaceProbe.Operation winner,
                                          TeamOrgInviteRaceProbe probe) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        List<Future<Result>> futures = new ArrayList<>();
        try {
            Callable<Result> accept = () -> probe.run(TeamOrgInviteRaceProbe.Operation.ACCEPT, () -> raceRequest(acceptRequest(fixture)));
            Callable<Result> cancel = () -> probe.run(TeamOrgInviteRaceProbe.Operation.CANCEL, () -> raceRequest(cancelRequest(fixture)));
            if (winner == null) {
                for (Callable<Result> task : List.of(accept, cancel)) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("同時start期限超過（harness障害）");
                        }
                        return task.call();
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).as("同時start準備").isTrue();
                start.countDown();
            } else {
                Future<Result> first = executor.submit(winner == TeamOrgInviteRaceProbe.Operation.ACCEPT ? accept : cancel);
                probe.awaitWinner();
                Future<Result> second = executor.submit(winner == TeamOrgInviteRaceProbe.Operation.ACCEPT ? cancel : accept);
                probe.awaitLoser();
                probe.release();
                futures.add(winner == TeamOrgInviteRaceProbe.Operation.ACCEPT ? first : second);
                futures.add(winner == TeamOrgInviteRaceProbe.Operation.ACCEPT ? second : first);
            }
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException failure) {
                    throw new AssertionError("競合Future失敗（message非採取）: "
                            + TeamOrgInviteRaceProbe.causes(failure) + ", " + probe.diagnostic());
                }
            }
            return results;
        } finally {
            start.countDown();
            probe.release();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("競合executorの異常停止").isTrue();
                throw new AssertionError("競合executorが正常終了期限超過（合格にはしない）");
            }
        }
    }

    private MockHttpServletRequestBuilder acceptRequest(RaceFixture fixture) {
        return post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", fixture.team().slug(), fixture.membershipId())
                .with(user(String.valueOf(fixture.teamAdmin())));
    }

    private MockHttpServletRequestBuilder cancelRequest(RaceFixture fixture) {
        return delete("/api/v1/organizations/{slug}/team-invites/{teamSlug}", fixture.org().slug(), fixture.team().slug())
                .with(user(String.valueOf(fixture.orgAdmin())));
    }

    private Result raceRequest(MockHttpServletRequestBuilder request) {
        try {
            return perform(request);
        } catch (Exception failure) {
            return new Result(-1, null, 0L, TeamOrgInviteRaceProbe.causes(failure));
        }
    }

    private void observeRaceAudit(RaceFixture fixture, TeamOrgInviteRaceProbe probe) {
        TeamAffiliationAuditRecorder target = AopTestUtils.getUltimateTargetObject(auditSpy);
        doAnswer(invocation -> {
            if (!probe.isCurrentOperation()) {
                return invocation.callRealMethod();
            }
            probe.stage("audit-entry");
            try {
                Object result = invocation.callRealMethod();
                probe.stage("audit-return");
                return result;
            } catch (Throwable failure) {
                probe.failure("audit-delegate", failure);
                throw failure;
            }
        }).when(target).record(any(AuditEventType.class), any(), eq(fixture.team().id()), eq(fixture.org().id()), any());
    }

    private RaceEffects raceEffects(RaceFixture fixture) {
        return new RaceEffects(
                countRaceRows("SELECT COUNT(*) FROM team_notification_outbox WHERE idempotency_key = ?",
                        uuidBytes(idempotencyKeyOf(NotificationType.TEAM_ORG_INVITE_ACCEPTED, fixture.membershipId()))),
                countRaceRows("SELECT COUNT(*) FROM audit_logs WHERE event_type = 'TEAM_ORG_INVITE_ACCEPTED' AND team_id = ? AND organization_id = ?", fixture.team().id(), fixture.org().id()),
                countRaceRows("SELECT COUNT(*) FROM audit_logs WHERE event_type = 'TEAM_ORG_INVITE_CANCELLED' AND team_id = ? AND organization_id = ?", fixture.team().id(), fixture.org().id()),
                countRaceRows("SELECT COUNT(*) FROM team_org_affiliation_restrictions WHERE team_id = ? AND organization_id = ? AND direction = 'ORG_INVITE'", fixture.team().id(), fixture.org().id()));
    }

    private long countRaceRows(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    // =====================================================================

    /** 結果（HTTP ステータス・エラーコード・作成された加盟 ID）。 */
    private record Result(int status, String errorCode, long id, List<String> exceptionClasses) {
    }

    private Result invite(long actor, String orgSlug, String teamSlug) throws Exception {
        return perform(post("/api/v1/organizations/{slug}/team-invites", orgSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("teamSlug", teamSlug))));
    }

    private Result perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        if (content.isBlank()) {
            return new Result(status, null, 0L, TeamOrgInviteRaceProbe.causes(result.getResolvedException()));
        }
        JsonNode body = objectMapper.readTree(content);
        long id = body.path("data").path("id").asLong(0L);
        return new Result(status, body.path("error").path("code").asText(null), id,
                TeamOrgInviteRaceProbe.causes(result.getResolvedException()));
    }

    /** 全タスクを同時にスタートさせ、結果を投入順に返す。 */
    private List<Result> runConcurrently(List<Callable<Result>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();
            for (Callable<Result> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** フィクスチャ作りをトランザクションに包んでコミットする。 */
    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    /** 別の操作の差し込みで観測した加盟行（{@code status/direction}）と、差し込んだ更新の影響行数。 */
    private static final class Injection {
        private final List<String> observedRows = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Integer> affectedRows = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    /** チームと組織の加盟行の {@code status/direction}（無ければ null。2行以上あれば失敗）。 */
    private String membershipRowState(long teamId, long orgId) {
        List<String> rows = jdbc.queryForList(
                "SELECT CONCAT(status, '/', direction) FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                String.class, teamId, orgId);
        if (rows.size() > 1) {
            throw new IllegalStateException("加盟行が2行以上ある: " + rows);
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long membershipCount(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> notificationRows(String type, long[] users) {
        String in = java.util.Arrays.stream(users).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForList("SELECT user_id, action_url FROM notifications WHERE notification_type = ? "
                + "AND user_id IN (" + in + ") ORDER BY user_id", type);
    }

    /** fan-out ジョブ行の受信者の解決方式（scope_type / scope_ref）と出どころ。 */
    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap(
                "SELECT scope_type, scope_ref, organization_id, source_id FROM notification_fanout_jobs WHERE id = ?",
                uuidBytes(jobId));
    }

    /** 冪等キー（F01.2.1 §6.7: {@code F01.2.1:<type>:<membershipId>}）。 */
    private static UUID idempotencyKeyOf(NotificationType type, long membershipId) {
        return UUID.nameUUIDFromBytes(("F01.2.1:" + type.name() + ":" + membershipId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 冪等キーで引いた outbox の行の状態（無ければ null）。 */
    private String outboxStatus(UUID key) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM team_notification_outbox WHERE idempotency_key = ?", String.class, uuidBytes(key));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long outboxCountByOrganization(long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_notification_outbox WHERE organization_id = ?", Long.class, orgId);
        return count == null ? 0 : count;
    }

    private long jobCountByKey(UUID key) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_event_uuid = ?", Long.class,
                uuidBytes(key));
        return count == null ? 0 : count;
    }

    private long jobCountByOrganization(long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE organization_id = ?", Long.class, orgId);
        return count == null ? 0 : count;
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    private UUID jobIdOf(long membershipId, String notificationType) {
        byte[] raw = jdbc.queryForObject(
                "SELECT id FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                        + "AND source_id = ? AND notification_type = ?", byte[].class, membershipId, notificationType);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
