package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.organization.event.OrganizationArchivedEvent;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 2-D — 組織・チームのアーカイブ／論理削除に伴う片付け（§4.5）の統合テスト（試練）。
 *
 * <p>組織のアーカイブ・組織の削除・チームの削除の片付けは AFTER_COMMIT のイベント経由で、チームのアーカイブは
 * 同じトランザクションの中で行う。コミットを伴うので、テストメソッドにトランザクションを張らず、フィクスチャは
 * トランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。イベントのリスナーが非同期でも通るよう、
 * 片付けの完了は {@code awaitCondition} で待つ（チームのアーカイブだけは同じトランザクションなので待たずに確かめる）。</p>
 *
 * <p>「残った側」への通知（{@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM}）は、組織側がアーカイブ・削除されたらチームの
 * 加盟操作者へ、チーム側がアーカイブ・削除されたら組織の ADMIN へ届く（方向を問わず、PENDING の相手側）。
 * 開く画面は、申請（TEAM_APPLY）なら {@code ?view=applications}、招待（ORG_INVITE）なら {@code ?view=invites}。</p>
 *
 * <ul>
 *   <li>AC-E05（リスナー）・G117g: 組織のアーカイブで PENDING が消えて取消の通知が届き、ACTIVE は残る</li>
 *   <li>AC-C13: 組織のアーカイブ後の承認は、イベント処理の前なら 422 ORG_003（行は PENDING のまま）、後なら 404 TEAM_070</li>
 *   <li>AC-C14: 申請中にチームが削除されると、イベント処理後に行が消えて組織 ADMIN に通知が届く。処理前に承認すると 404</li>
 *   <li>AC-G105: チームのアーカイブは同じトランザクションで PENDING（両方向）を消し、相手側に取消を通知する。ACTIVE は残る</li>
 *   <li>AC-G106: 組織の論理削除でグループは同じトランザクションで論理削除され、制限は物理削除され、PENDING の相手側に通知が届く</li>
 *   <li>AC-G107: チームの論理削除で PENDING・ACTIVE・制限が消える</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-D アーカイブ・削除の片付け（コミットを伴う検証）")
class TeamOrgLifecycleCleanupCommittedIT extends TeamOrgLifecycleCommittedItSupport {

    private static final String CANCELLED = "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TeamService teamService;

    @Autowired
    private OrganizationService organizationService;

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-E05（リスナー）・G117g 組織のアーカイブ
    // =====================================================================

    @Test
    @DisplayName("AC-E05・G117g OrganizationArchivedEvent で、当該組織の PENDING（申請・招待）だけが消え、ACTIVE と他組織の PENDING は残る。"
            + "取消の通知はチームの加盟操作者（TA・TG）と招待先チームの ADMIN に届く")
    void 組織アーカイブイベントでPENDINGだけが消える() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        TeamWithAdmin activeTeam = extraTeam();
        OrgFx orgY = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long invite = membership(inviteTeam.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);
        long active = membership(activeTeam.team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);
        long otherOrgPending = membership(fx.team().id(), orgY.id(), "PENDING", "TEAM_APPLY", null);

        inTxVoid(() -> eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id())));

        awaitCondition("組織の PENDING が消える", () -> statusOf(apply) == null && statusOf(invite) == null);
        assertThat(statusOf(active)).as("ACTIVE は残す").isEqualTo("ACTIVE");
        assertThat(statusOf(otherOrgPending)).as("他組織への PENDING には触れない").isEqualTo("PENDING");
        assertThat(jobIdsOf(active, CANCELLED)).isEmpty();
        assertThat(jobIdsOf(otherOrgPending, CANCELLED)).isEmpty();

        // 申請（TEAM_APPLY）: 申請したチームの加盟操作者へ
        UUID applyJob = singleJob(apply, CANCELLED);
        Map<String, Object> applyRow = jobRow(applyJob);
        assertThat(applyRow.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(applyRow.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
        assertThat(applyRow.get("action_url")).isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        assertThat(((Number) applyRow.get("organization_id")).longValue()).isEqualTo(fx.org().id());
        deliver(applyJob);
        List<Map<String, Object>> applyRows = notificationsOf(CANCELLED,
                fx.ta(), fx.tg(), fx.tm(), fx.td(), fx.xa(), fx.xa2(), fx.xd());
        assertThat(userIdsOf(applyRows)).as("TA・TG にだけ届く（TM・TD・組織側には届かない）")
                .containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(applyRows).extracting(r -> r.get("source_type")).containsOnly("TEAM_ORG_MEMBERSHIP");
        assertThat(applyRows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.org().name()));

        // 招待（ORG_INVITE）: 招待されたチームの加盟操作者へ。開く画面は ?view=invites
        UUID inviteJob = singleJob(invite, CANCELLED);
        Map<String, Object> inviteRow = jobRow(inviteJob);
        assertThat(inviteRow.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(inviteRow.get("scope_ref")).isEqualTo(String.valueOf(inviteTeam.team().id()));
        assertThat(inviteRow.get("action_url"))
                .isEqualTo("/teams/" + inviteTeam.team().slug() + "/affiliations?view=invites");
        deliver(inviteJob);
        assertThat(userIdsOf(notificationsOf(CANCELLED, inviteTeam.admin()))).containsExactly(inviteTeam.admin());
    }

    @Test
    @DisplayName("AC-E05 組織のアーカイブ API（ADMIN）を実際に叩くと、PENDING が消えて取消の通知ジョブが作られ、ACTIVE は残る")
    void 組織アーカイブAPIでPENDINGが片付く() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin activeTeam = extraTeam();
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(activeTeam.team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);

        assertThat(archiveOrgApi(fx.xa(), fx.org().slug()).status()).isEqualTo(200);

        awaitCondition("組織の PENDING が消える", () -> statusOf(apply) == null);
        assertThat(statusOf(active)).isEqualTo("ACTIVE");
        UUID job = singleJob(apply, CANCELLED);
        assertThat(jobRow(job).get("action_url"))
                .isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
    }

    // =====================================================================
    // AC-C13 組織のアーカイブ後の承認
    // =====================================================================

    @Test
    @DisplayName("AC-C13 組織のアーカイブ後の承認は、イベント処理の前（PENDING が残っている間）は 422 ORG_003 で行は PENDING のまま、"
            + "処理の後（行が消えた後）は 404 TEAM_070")
    void 組織アーカイブ後の承認はイベント処理の前後で応答が変わる() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        // 処理の前: アーカイブだけを反映し、イベントはまだ処理されていない状態
        inTxVoid(() -> archiveOrganization(fx.org().id()));
        Result before = approve(fx.xa(), fx.org().slug(), id);
        assertThat(before.status()).isEqualTo(422);
        assertThat(before.errorCode()).isEqualTo("ORG_003");
        assertThat(statusOf(id)).as("承認は行を変えない").isEqualTo("PENDING");

        // 処理の後: リスナーが PENDING を削除する
        inTxVoid(() -> eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id())));
        awaitCondition("組織の PENDING が消える", () -> statusOf(id) == null);
        Result after = approve(fx.xa(), fx.org().slug(), id);
        assertThat(after.status()).isEqualTo(404);
        assertThat(after.errorCode()).isEqualTo("TEAM_070");
    }

    // =====================================================================
    // AC-C14 申請中のチームの削除
    // =====================================================================

    @Test
    @DisplayName("AC-C14 申請中にチームが削除されると、処理前の承認は 404 TEAM_070（行は残る）。処理後は行が消え、組織 ADMIN（XA・XA2）に通知が届く")
    void 申請中のチーム削除() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        // 処理の前: チームの論理削除だけを反映し、イベントはまだ処理されていない状態
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());
        Result before = approve(fx.xa(), fx.org().slug(), id);
        assertThat(before.status()).isEqualTo(404);
        assertThat(before.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(id)).as("承認は行を変えない").isEqualTo("PENDING");
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET deleted_at = NULL WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());

        // 本物のフロー: チーム削除 API → TeamDeletedEvent
        assertThat(deleteTeamApi(fx.ta(), fx.team().slug()).status()).isEqualTo(204);
        awaitCondition("チームの PENDING が消える", () -> statusOf(id) == null);

        UUID jobId = singleJob(id, CANCELLED);
        Map<String, Object> job = jobRow(jobId);
        assertThat(job.get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
        assertThat(job.get("action_url"))
                .isEqualTo("/organizations/" + fx.org().slug() + "/member-teams?view=applications");
        deliver(jobId);
        List<Map<String, Object>> rows = notificationsOf(CANCELLED,
                fx.xa(), fx.xa2(), fx.xd(), fx.ta(), fx.tg(), fx.tm(), fx.td());
        assertThat(userIdsOf(rows)).as("組織の ADMIN にだけ届く").containsExactlyInAnyOrder(fx.xa(), fx.xa2());
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.team().name()));
    }

    // =====================================================================
    // AC-G105・G117g チームのアーカイブ（同じトランザクション）
    // =====================================================================

    @Test
    @DisplayName("AC-G105・G117g チームをアーカイブすると、同じ API 呼び出しの中で PENDING（申請・招待の両方向）が消え、"
            + "相手側の ADMIN に取消が届く。ACTIVE は残る")
    void チームアーカイブでPENDINGが両方向消える() throws Exception {
        Fixture fx = fixture();
        OrgFx inviter = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long inviterAdmin = inTx(() -> {
            long u = newUser();
            makeOrgAdmin(u, inviter.id());
            return u;
        });
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long invite = membership(fx.team().id(), inviter.id(), "PENDING", "ORG_INVITE", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);

        assertThat(archiveTeamApi(fx.ta(), fx.team().slug()).status()).isEqualTo(200);

        // 同じトランザクションなので、待たずにコミット済みの結果が見える
        assertThat(statusOf(apply)).isNull();
        assertThat(statusOf(invite)).isNull();
        assertThat(statusOf(active)).as("ACTIVE は残す").isEqualTo("ACTIVE");

        UUID applyJob = singleJob(apply, CANCELLED);
        Map<String, Object> applyRow = jobRow(applyJob);
        assertThat(applyRow.get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(applyRow.get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
        assertThat(applyRow.get("action_url"))
                .isEqualTo("/organizations/" + fx.org().slug() + "/member-teams?view=applications");
        deliver(applyJob);
        assertThat(userIdsOf(notificationsOf(CANCELLED, fx.xa(), fx.xa2(), fx.xd(), inviterAdmin)))
                .as("申請の取消は申請先組織の ADMIN にだけ届く").containsExactlyInAnyOrder(fx.xa(), fx.xa2());

        UUID inviteJob = singleJob(invite, CANCELLED);
        Map<String, Object> inviteRow = jobRow(inviteJob);
        assertThat(inviteRow.get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(inviteRow.get("scope_ref")).isEqualTo(String.valueOf(inviter.id()));
        assertThat(inviteRow.get("action_url"))
                .isEqualTo("/organizations/" + inviter.slug() + "/member-teams?view=invites");
        deliver(inviteJob);
        assertThat(userIdsOf(notificationsOf(CANCELLED, inviterAdmin))).containsExactly(inviterAdmin);
        assertThat(jobIdsOf(active, CANCELLED)).isEmpty();
    }

    @Test
    @DisplayName("AC-G105 チームのアーカイブは PENDING の削除と通知ジョブの enqueue を同じトランザクションで行う"
            + "（トランザクションの中で見え、ロールバックすると何も残らない）")
    void チームアーカイブのPENDING削除と通知は同じトランザクション() {
        Fixture fx = fixture();
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        OrgFx inviter = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long invite = membership(fx.team().id(), inviter.id(), "PENDING", "ORG_INVITE", null);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            teamService.archiveTeam(fx.team().id());
            Number pendingInTx = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = :id AND status = 'PENDING'")
                    .setParameter("id", fx.team().id()).getSingleResult();
            Number jobsInTx = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                                    + "AND notification_type = :type AND source_id IN (:ids)")
                    .setParameter("type", CANCELLED)
                    .setParameter("ids", List.of(apply, invite))
                    .getSingleResult();
            assertThat(pendingInTx.longValue()).as("トランザクションの中で PENDING が消えている").isZero();
            assertThat(jobsInTx.longValue()).as("トランザクションの中で通知ジョブが2件 enqueue されている").isEqualTo(2);
            status.setRollbackOnly();
        });

        assertThat(statusOf(apply)).as("ロールバックすれば PENDING は戻る").isEqualTo("PENDING");
        assertThat(statusOf(invite)).isEqualTo("PENDING");
        assertThat(jobIdsOf(apply, CANCELLED)).as("ロールバックすれば通知ジョブも残らない").isEmpty();
        assertThat(jobIdsOf(invite, CANCELLED)).isEmpty();
    }

    // =====================================================================
    // AC-G106・G117g 組織の論理削除
    // =====================================================================

    @Test
    @DisplayName("AC-G106 組織の論理削除は、チームグループの論理削除を同じトランザクションで行う（中で見え、ロールバックすると戻る）")
    void 組織削除のグループ論理削除は同じトランザクション() {
        Fixture fx = fixture();
        inTxVoid(() -> {
            newGroup(fx.org().id(), "北地区", false);
            newGroup(fx.org().id(), "南地区", false);
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            organizationService.deleteOrganization(fx.org().id(), fx.xa());
            Number alive = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = :id AND deleted_at IS NULL")
                    .setParameter("id", fx.org().id()).getSingleResult();
            assertThat(alive.longValue()).as("トランザクションの中でグループが論理削除されている").isZero();
            status.setRollbackOnly();
        });

        Long aliveAfterRollback = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ? AND deleted_at IS NULL",
                Long.class, fx.org().id());
        assertThat(aliveAfterRollback).as("ロールバックすればグループは生きている").isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-G106・G117g 組織を論理削除すると、グループは論理削除され、制限は物理削除され、PENDING は消えて"
            + "チームの加盟操作者に取消が届く。他組織のグループ・制限には触れない")
    void 組織の論理削除() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin otherTeam = extraTeam();
        OrgFx other = inTx(() -> newOrg(true, true, "OPTIONAL", "PUBLIC"));
        inTxVoid(() -> {
            newGroup(fx.org().id(), "北地区", false);
            newGroup(fx.org().id(), "南地区", false);
            newGroup(other.id(), "他組織のグループ", false);
        });
        blockRestriction(fx.team().id(), fx.org().id(), "TEAM_APPLY");
        blockRestriction(otherTeam.team().id(), fx.org().id(), "ORG_INVITE");
        blockRestriction(fx.team().id(), other.id(), "TEAM_APPLY");
        long pending = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        assertThat(deleteOrgApi(fx.xa(), fx.org().slug()).status()).isEqualTo(204);

        Long aliveGroups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ? AND deleted_at IS NULL",
                Long.class, fx.org().id());
        assertThat(aliveGroups).as("グループは同じトランザクションで論理削除される（待たずに確かめる）").isZero();
        Long totalGroups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ?", Long.class, fx.org().id());
        assertThat(totalGroups).as("グループは物理削除ではなく論理削除").isEqualTo(2L);
        Long otherGroups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ? AND deleted_at IS NULL",
                Long.class, other.id());
        assertThat(otherGroups).isEqualTo(1L);

        awaitCondition("組織の制限と PENDING が片付く", () -> restrictionCountOfOrg(fx.org().id()) == 0
                && statusOf(pending) == null);
        assertThat(restrictionCountOfOrg(other.id())).as("他組織の制限には触れない").isEqualTo(1L);

        UUID jobId = singleJob(pending, CANCELLED);
        Map<String, Object> job = jobRow(jobId);
        assertThat(job.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
        assertThat(job.get("action_url")).isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        deliver(jobId);
        List<Map<String, Object>> rows = notificationsOf(CANCELLED,
                fx.ta(), fx.tg(), fx.tm(), fx.td(), fx.xa(), fx.xa2());
        assertThat(userIdsOf(rows)).containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.org().name()));
    }

    // =====================================================================
    // AC-G107 チームの論理削除
    // =====================================================================

    @Test
    @DisplayName("AC-G107 チームを論理削除すると、そのチームの PENDING・ACTIVE・制限が消える。他のチームの行には触れない。"
            + "通知は PENDING の相手側にだけ送る（ACTIVE の相手には送らない）")
    void チームの論理削除() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin otherTeam = extraTeam();
        OrgFx orgY = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        OrgFx orgZ = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long pending = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), orgY.id(), "ACTIVE", "ORG_INVITE", null);
        long othersActive = membership(otherTeam.team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);
        blockRestriction(fx.team().id(), orgZ.id(), "TEAM_APPLY");
        blockRestriction(otherTeam.team().id(), orgZ.id(), "TEAM_APPLY");

        assertThat(deleteTeamApi(fx.ta(), fx.team().slug()).status()).isEqualTo(204);

        awaitCondition("チームの加盟・制限が片付く", () -> statusOf(pending) == null && statusOf(active) == null
                && restrictionCountOfTeam(fx.team().id()) == 0);
        assertThat(statusOf(othersActive)).as("他のチームの加盟には触れない").isEqualTo("ACTIVE");
        assertThat(restrictionCountOfTeam(otherTeam.team().id())).isEqualTo(1L);
        assertThat(jobIdsOf(pending, CANCELLED)).hasSize(1);
        assertThat(jobIdsOf(active, CANCELLED)).as("ACTIVE の相手側には取消の通知を送らない").isEmpty();
        Long activeJobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                        + "AND source_id = ?", Long.class, active);
        assertThat(activeJobs).isZero();
    }
}
