package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.organization.event.OrganizationArchivedEvent;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.batch.TeamOrgLifecycleCleanupBatch;
import com.mannschaft.app.team.service.TeamOrgLifecycleCleanupService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *   <li>AC-C13: 組織のアーカイブ後の承認は、イベント処理の前なら既存のアーカイブ拒否 409 ORG_003（行は PENDING のまま）、後なら 404 TEAM_070</li>
 *   <li>AC-C14: 申請中にチームが削除されると、イベント処理後に行が消えて組織 ADMIN に通知が届く。処理前に承認すると 404</li>
 *   <li>AC-G105: チームのアーカイブは同じトランザクションで PENDING（両方向）を消し、相手側に取消を通知する。ACTIVE は残る</li>
 *   <li>AC-G106: 組織の論理削除でグループは同じトランザクションで論理削除され、制限は物理削除され、PENDING の相手側に通知が届く</li>
 *   <li>AC-G107: チームの論理削除で PENDING・ACTIVE・制限が消える</li>
 * </ul>
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, addFilters = true)
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

    @Autowired
    private TeamOrgLifecycleCleanupService cleanupService;

    @Autowired
    private TeamOrgLifecycleCleanupBatch cleanupBatch;

    @Autowired
    @Qualifier("event-pool")
    private ThreadPoolTaskExecutor eventPool;

    @AfterEach
    void cleanUp() throws Exception {
        awaitCondition("所有fixtureを削除する前に監査・イベント処理が完了する", () -> eventPool.getActiveCount() == 0
                && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
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

        // 片付けは状態に基づく（2-D）ので、イベントと一緒に組織のアーカイブそのものもコミットする
        inTxVoid(() -> {
            archiveOrganization(fx.org().id());
            eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id()));
        });

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
    @DisplayName("AC-C13 組織のアーカイブ後の承認は、イベント処理の前（PENDING が残っている間）は既存のアーカイブ拒否（409 ORG_003）で行は PENDING のまま、"
            + "処理の後（行が消えた後）は 404 TEAM_070")
    void 組織アーカイブ後の承認はイベント処理の前後で応答が変わる() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        // 処理の前: アーカイブだけを反映し、イベントはまだ処理されていない状態
        inTxVoid(() -> archiveOrganization(fx.org().id()));
        Result before = approve(fx.xa(), fx.org().slug(), id);
        // 殿の判断（2-D 出陣）: 「既存のアーカイブ拒否」を正とし、2-B1・2-B2 の状態の再確認と同じ応答（ORG_003 → 409）に揃える。
        // 設計書 §4.5・AC-C13 の「422」表記は 9-A で直す
        assertThat(before.status()).isEqualTo(409);
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
            + "（team の outbox への予約がトランザクションの中で見え、ロールバックすると何も残らない）")
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
            // 通知は team の outbox に同じ tx で1行ずつ書かれる（fan-out ジョブはコミット後に relay が作る。§6.7・OB02）。
            // 冪等キー（F01.2.1 §6.7）で、取り消した2件の加盟それぞれの行を数える
            Number jobsInTx = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM team_notification_outbox WHERE notification_type = :type "
                                    + "AND idempotency_key IN (:keys)")
                    .setParameter("type", CANCELLED)
                    .setParameter("keys", List.of(cancelledKey(apply), cancelledKey(invite)))
                    .getSingleResult();
            assertThat(pendingInTx.longValue()).as("トランザクションの中で PENDING が消えている").isZero();
            assertThat(jobsInTx.longValue()).as("トランザクションの中で通知が2件 outbox に予約されている").isEqualTo(2);
            status.setRollbackOnly();
        });

        assertThat(statusOf(apply)).as("ロールバックすれば PENDING は戻る").isEqualTo("PENDING");
        assertThat(statusOf(invite)).isEqualTo("PENDING");
        assertThat(jobIdsOf(apply, CANCELLED)).as("ロールバックすれば通知ジョブも残らない").isEmpty();
        assertThat(jobIdsOf(invite, CANCELLED)).isEmpty();
    }

    /** 取消の通知の冪等キー（F01.2.1 §6.7: {@code "F01.2.1:" + notificationType + ":" + membershipId}）。 */
    private static byte[] cancelledKey(long membershipId) {
        return uuidBytes(UUID.nameUUIDFromBytes(("F01.2.1:" + CANCELLED + ":" + membershipId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

    // =====================================================================
    // 2-D 検分差し戻し: 状態に基づく片付け・回復バッチ・取消の通知の規則
    // =====================================================================

    @Test
    @DisplayName("2-D 差し戻し②: アーカイブ → 解除 → 新しい申請の後に、遅れて古いアーカイブの片付けが走っても、"
            + "組織の現在の状態（未アーカイブ）をロックの下で確かめて何もしない（新しい申請を消さず、取消も送らない）")
    void 解除後に遅れて走った古い片付けは新しい申請を消さない() throws Exception {
        Fixture fx = fixture();
        // アーカイブ（イベントはまだ処理されていない）→ 解除 → 新しい申請（201）
        inTxVoid(() -> archiveOrganization(fx.org().id()));
        inTxVoid(() -> em.createNativeQuery("UPDATE organizations SET archived_at = NULL WHERE id = :id")
                .setParameter("id", fx.org().id()).executeUpdate());
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請は通る: %s", applied).isEqualTo(201);
        Long id = jdbc.queryForObject("SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, fx.team().id(), fx.org().id());

        // 遅れて届いた古いアーカイブの片付け（リスナーが呼ぶのと同じ入口を同期で呼ぶ）
        assertThat(cleanupService.cleanUpOrganization(fx.org().id())).as("何も消さない").isZero();
        // イベント経由（非同期のリスナー）でも同じ
        inTxVoid(() -> eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id())));
        Thread.sleep(1500);

        assertThat(statusOf(id)).as("解除後の新しい申請は残る").isEqualTo("PENDING");
        assertThat(jobIdsOf(id, CANCELLED)).as("取消の通知も送らない").isEmpty();
    }

    @Test
    @DisplayName("2-D 差し戻し③: アーカイブの直後に組織が論理削除されても、片付けは論理削除済みの組織名を引いて、"
            + "取消の通知を省かずに予約する")
    void アーカイブ直後に論理削除された組織でも取消の通知を省かない() throws Exception {
        Fixture fx = fixture();
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        // アーカイブのイベントが処理される前に、組織が論理削除される
        inTxVoid(() -> {
            archiveOrganization(fx.org().id());
            em.createNativeQuery("UPDATE organizations SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                    .setParameter("id", fx.org().id()).executeUpdate();
            eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id()));
        });

        awaitCondition("組織の PENDING が消える", () -> statusOf(apply) == null);
        UUID job = singleJob(apply, CANCELLED);
        assertThat(jobRow(job).get("action_url"))
                .isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        deliver(job);
        List<Map<String, Object>> rows = notificationsOf(CANCELLED, fx.ta(), fx.tg());
        assertThat(userIdsOf(rows)).containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .as("論理削除済みでも組織名が文面に入る")
                .allSatisfy(body -> assertThat(body).contains(fx.org().name()));
    }

    @Test
    @DisplayName("2-D 差し戻し④: 受信通知の予約の前（チームが知らない）の招待は、組織の片付けで消えても取消を通知しない。"
            + "受信通知を予約済みの招待には取消が届く")
    void チームが知らない招待には取消を通知しない() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin knownTeam = extraTeam();
        long unknown = unnotifiedInvite(fx.team().id(), fx.org().id());
        long known = membership(knownTeam.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);

        inTxVoid(() -> {
            archiveOrganization(fx.org().id());
            eventPublisher.publishEvent(new OrganizationArchivedEvent(fx.xa(), fx.org().id()));
        });

        awaitCondition("組織の PENDING が消える", () -> statusOf(unknown) == null && statusOf(known) == null);
        assertThat(jobIdsOf(unknown, CANCELLED)).as("知らない招待の取消は送らない").isEmpty();
        assertThat(jobIdsOf(known, CANCELLED)).as("知っている招待の取消は送る").hasSize(1);
    }

    @Test
    @DisplayName("2-D 差し戻し①・AC-E05（保険）: リスナーが走らなかった（イベントの取りこぼし）組織のアーカイブ・論理削除と"
            + "チームの論理削除を、回復バッチ team-org-lifecycle-cleanup が状態から拾って片付け、取消を予約する。生きている組織・チームには触れない")
    void 回復バッチがイベントの取りこぼしを片付ける() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        TeamWithAdmin deletedTeam = extraTeam();
        OrgFx deletedOrg = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        OrgFx aliveOrg = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long archivedApply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long archivedInvite = membership(inviteTeam.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);
        long archivedActive = membership(deletedTeam.team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);
        long deletedOrgPending = membership(fx.team().id(), deletedOrg.id(), "PENDING", "TEAM_APPLY", null);
        blockRestriction(inviteTeam.team().id(), deletedOrg.id(), "TEAM_APPLY");
        long deletedTeamPending = membership(deletedTeam.team().id(), aliveOrg.id(), "PENDING", "TEAM_APPLY", null);
        blockRestriction(deletedTeam.team().id(), aliveOrg.id(), "ORG_INVITE");
        long alivePending = membership(inviteTeam.team().id(), aliveOrg.id(), "PENDING", "TEAM_APPLY", null);

        // イベントを出さずに状態だけをコミットする（リスナーの前にプロセスが止まった状況）
        inTxVoid(() -> {
            archiveOrganization(fx.org().id());
            em.createNativeQuery("UPDATE organizations SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                    .setParameter("id", deletedOrg.id()).executeUpdate();
            em.createNativeQuery("UPDATE teams SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                    .setParameter("id", deletedTeam.team().id()).executeUpdate();
        });
        assertThat(statusOf(archivedApply)).as("バッチの前は残っている").isEqualTo("PENDING");

        cleanupBatch.cleanUp();

        // 組織のアーカイブ: PENDING は消えて取消が予約され、ACTIVE は（チームの論理削除で消えるまでは）組織側では触らない
        assertThat(statusOf(archivedApply)).isNull();
        assertThat(statusOf(archivedInvite)).isNull();
        assertThat(jobIdsOf(archivedApply, CANCELLED)).hasSize(1);
        assertThat(jobIdsOf(archivedInvite, CANCELLED)).hasSize(1);
        // 組織の論理削除: PENDING と制限が消える
        assertThat(statusOf(deletedOrgPending)).isNull();
        assertThat(jobIdsOf(deletedOrgPending, CANCELLED)).hasSize(1);
        assertThat(restrictionCountOfOrg(deletedOrg.id())).isZero();
        // チームの論理削除: PENDING・ACTIVE・制限が消え、PENDING の相手（生きている組織）へ取消が届く
        assertThat(statusOf(deletedTeamPending)).isNull();
        assertThat(statusOf(archivedActive)).isNull();
        assertThat(restrictionCountOfTeam(deletedTeam.team().id())).isZero();
        assertThat(jobIdsOf(deletedTeamPending, CANCELLED)).hasSize(1);
        assertThat(jobIdsOf(archivedActive, CANCELLED)).as("ACTIVE の相手には送らない").isEmpty();
        // 生きている組織・チームの組み合わせには触れない
        assertThat(statusOf(alivePending)).isEqualTo("PENDING");
        assertThat(jobIdsOf(alivePending, CANCELLED)).isEmpty();

        // 冪等: もう一度走らせても何も変わらず、通知も増えない
        cleanupBatch.cleanUp();
        assertThat(jobIdsOf(archivedApply, CANCELLED)).hasSize(1);
        assertThat(statusOf(alivePending)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("2-D D-3T 根治: 他ドメインの tx からのチームのアーカイブ（archiveTeamDeferringAffiliationCleanup）は、"
            + "コミット後に team ドメインが自分の tx で PENDING を片付けて取消を予約する。ACTIVE は残す")
    void 他ドメインからのチームアーカイブはコミット後に片付く() throws Exception {
        Fixture fx = fixture();
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            teamService.archiveTeamDeferringAffiliationCleanup(fx.team().id());
            Number pendingInTx = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = :id AND status = 'PENDING'")
                    .setParameter("id", fx.team().id()).getSingleResult();
            assertThat(pendingInTx.longValue()).as("呼び出し元の tx の中では加盟に触れない").isEqualTo(1L);
        });

        awaitCondition("コミット後にチームの PENDING が消える", () -> statusOf(apply) == null);
        assertThat(statusOf(active)).as("ACTIVE は残す").isEqualTo("ACTIVE");
        UUID job = singleJob(apply, CANCELLED);
        assertThat(jobRow(job).get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(jobRow(job).get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
    }

    // =====================================================================
    // 2-D 検分差し戻し（2巡目）①: 片付けの前にアーカイブ解除されても、閉鎖より前の PENDING を復元しない（§4.5）
    // =====================================================================

    /** 組織を「アーカイブ（閉鎖の世代を +1）→ 片付けの前に解除」した状態にする（イベントは出さない）。 */
    private void archiveThenUnarchiveWithoutCleanup(long orgId) {
        inTxVoid(() -> em.createNativeQuery("UPDATE organizations SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", orgId).executeUpdate());
        inTxVoid(() -> em.createNativeQuery("UPDATE organizations SET archived_at = NULL WHERE id = :id")
                .setParameter("id", orgId).executeUpdate());
    }

    @Test
    @DisplayName("2-D 差し戻し（2巡目）①・§4.5: アーカイブ → 片付けの前に解除 → 片付け、の順でも、アーカイブより前の PENDING"
            + "（申請・招待）は消えて取消が予約される（解除で復元しない）。解除後の新しい申請は消さない")
    void 片付けの前に解除されてもアーカイブ前のPENDINGは消える() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin applyTeam = extraTeam();
        TeamWithAdmin inviteTeam = extraTeam();
        long oldApply = membership(applyTeam.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long oldInvite = membership(inviteTeam.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);

        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請は通る: %s", applied).isEqualTo(201);
        Long newApply = jdbc.queryForObject(
                "SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, fx.team().id(), fx.org().id());
        assertThat(statusOf(oldApply)).as("片付けの前はアーカイブ前の申請が残っている").isEqualTo("PENDING");

        // 遅れて届いたアーカイブの片付け（リスナーと同じ入口）
        assertThat(cleanupService.cleanUpOrganization(fx.org().id())).as("アーカイブ前の2件だけを消す").isEqualTo(2);

        assertThat(statusOf(oldApply)).as("アーカイブ前の申請は解除で復元しない").isNull();
        assertThat(statusOf(oldInvite)).as("アーカイブ前の招待も同じ").isNull();
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
        assertThat(jobIdsOf(oldInvite, CANCELLED)).hasSize(1);
        UUID job = singleJob(oldApply, CANCELLED);
        assertThat(jobRow(job).get("scope_ref")).isEqualTo(String.valueOf(applyTeam.team().id()));
        assertThat(statusOf(newApply)).as("解除後の新しい申請は残る").isEqualTo("PENDING");
        assertThat(jobIdsOf(newApply, CANCELLED)).isEmpty();

        // 冪等: もう一度呼んでも何も消さず、通知も増えない
        assertThat(cleanupService.cleanUpOrganization(fx.org().id())).isZero();
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
        assertThat(statusOf(newApply)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("2-D 差し戻し（2巡目）①・§4.5: 片付けの前に解除された組織のアーカイブ前の PENDING も、回復バッチが候補に拾って片付ける。"
            + "一度も閉じていない組織・解除後の申請には触れない")
    void 回復バッチは解除済み組織のアーカイブ前PENDINGを拾う() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin applyTeam = extraTeam();
        OrgFx aliveOrg = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long oldApply = membership(applyTeam.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long alivePending = membership(applyTeam.team().id(), aliveOrg.id(), "PENDING", "TEAM_APPLY", null);

        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請は通る: %s", applied).isEqualTo(201);
        Long newApply = jdbc.queryForObject(
                "SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, fx.team().id(), fx.org().id());

        cleanupBatch.cleanUp();

        assertThat(statusOf(oldApply)).as("アーカイブ前の申請はバッチが片付ける").isNull();
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
        assertThat(statusOf(newApply)).as("解除後の申請は残る").isEqualTo("PENDING");
        assertThat(statusOf(alivePending)).as("一度も閉じていない組織の PENDING には触れない").isEqualTo("PENDING");
        assertThat(jobIdsOf(alivePending, CANCELLED)).isEmpty();
    }

    @Test
    @DisplayName("2-D 差し戻し（2巡目）①・4巡目: 組織の論理削除の経路でも閉鎖の世代が +1 される")
    void 組織の論理削除で閉鎖の世代が進む() {
        Fixture fx = fixture();
        organizationService.deleteOrganization(fx.org().id(), fx.xa());

        assertThat(jdbc.queryForObject("SELECT closure_generation FROM organizations WHERE id = ?", Long.class,
                fx.org().id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("2-D 差し戻し（2巡目）①・§4.5: 他ドメインの tx からアーカイブされたチームが片付けの前にアーカイブ解除されても、"
            + "解除（unarchiveTeam）がチーム行のロックの下でアーカイブ前の PENDING を消して取消を予約する（復元しない）")
    void チームのアーカイブ解除はアーカイブ前のPENDINGを復元しない() throws Exception {
        Fixture fx = fixture();
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);
        // 片付けを伴わないアーカイブ（リスナーの前にプロセスが止まった状況）
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET archived_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());
        assertThat(statusOf(apply)).isEqualTo("PENDING");

        teamService.unarchiveTeam(fx.team().id());

        assertThat(statusOf(apply)).as("アーカイブ前の申請は解除で復元しない").isNull();
        assertThat(statusOf(active)).as("ACTIVE は残す").isEqualTo("ACTIVE");
        UUID job = singleJob(apply, CANCELLED);
        assertThat(jobRow(job).get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(jobRow(job).get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM teams WHERE id = ?", Boolean.class,
                fx.team().id())).as("解除は行われる").isTrue();
    }

    // =====================================================================
    // 2-D 検分差し戻し（3巡目）High 1: 解除の後に片付けの削除が永続化コンテキストをクリアしても、解除がコミットされる
    // =====================================================================

    /** チームを「アーカイブ（閉鎖の世代を +1）→ 片付けの前に解除」した状態にする（イベントも片付けも走らせない）。 */
    private void archiveTeamThenUnarchiveWithoutCleanup(long teamId) {
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", teamId).executeUpdate());
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET archived_at = NULL WHERE id = :id")
                .setParameter("id", teamId).executeUpdate());
    }

    private boolean teamArchived(long teamId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT archived_at IS NOT NULL FROM teams WHERE id = ?", Boolean.class, teamId));
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 1: 片付けをモックせず、PENDING が残ったアーカイブ済みチームをチームの解除 API で解除すると、"
            + "解除がコミットされてチームが ACTIVE（未アーカイブ）に戻り、残った PENDING は消えて取消が予約される。閉鎖の世代は戻らない")
    void チームの解除APIは片付けの後も解除がコミットされる() throws Exception {
        Fixture fx = fixture();
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);
        // 他ドメインの tx からのアーカイブの片付けが走らなかった状況（閉鎖の世代は進む）
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());

        Result result = unarchiveTeamApi(fx.ta(), fx.team().slug());

        assertThat(result.status()).as("解除 API: %s", result).isEqualTo(200);
        assertThat(teamArchived(fx.team().id())).as("解除がコミットされている（片付けの後の detach で失われない）").isFalse();
        assertThat(statusOf(apply)).as("残った PENDING は解除で復元しない").isNull();
        assertThat(statusOf(active)).as("ACTIVE は残す").isEqualTo("ACTIVE");
        assertThat(jobIdsOf(apply, CANCELLED)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT closure_generation FROM teams WHERE id = ?", Long.class,
                fx.team().id())).as("閉鎖の世代は解除しても戻らない").isEqualTo(1L);

        // 解除後は申請できる（解除後の申請は閉鎖前ではない）
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請: %s", applied).isEqualTo(201);
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 1: チーム自身のアーカイブ API も、片付けの削除の後でアーカイブと閉鎖の世代がコミットされている")
    void チームのアーカイブAPIは片付けの後もアーカイブがコミットされる() throws Exception {
        Fixture fx = fixture();
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);

        Result result = archiveTeamApi(fx.ta(), fx.team().slug());

        assertThat(result.status()).as("アーカイブ API: %s", result).isEqualTo(200);
        assertThat(teamArchived(fx.team().id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT closure_generation FROM teams WHERE id = ?", Long.class,
                fx.team().id())).as("閉鎖の世代が +1 される").isEqualTo(1L);
        assertThat(statusOf(apply)).isNull();
    }

    // =====================================================================
    // 2-D 検分差し戻し（3巡目）High 2: 閉鎖前の PENDING は、片付けの前に解除されても承認・承諾で ACTIVE にしない
    // =====================================================================

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 2・§4.5: 組織のアーカイブ前の申請が、片付けの前に解除されて残っていても、承認は 404 TEAM_070 で拒み"
            + "（ACTIVE にしない）、ロールバックの後にその行を片付けて取消を予約する。回復バッチの後も ACTIVE で残らない")
    void 組織の閉鎖前の申請は解除後も承認できない() throws Exception {
        Fixture fx = fixture();
        long oldApply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        archiveThenUnarchiveWithoutCleanup(fx.org().id());

        Result result = approve(fx.xa(), fx.org().slug(), oldApply);

        assertThat(result.status()).as("閉鎖前の申請の承認: %s", result).isEqualTo(404);
        assertThat(result.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(oldApply)).as("ACTIVE にしない").isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に閉鎖前の申請が片付く", () -> statusOf(oldApply) == null);
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);

        cleanupBatch.cleanUp();
        assertThat(rowCount(fx.team().id(), fx.org().id())).as("回復バッチの後も行は戻らない").isZero();
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 2・§4.5: 組織のアーカイブ前の招待が、片付けの前に解除されて残っていても、承諾は 404 TEAM_070 で拒み"
            + "（ACTIVE にしない）、ロールバックの後にその行を片付けて取消を予約する")
    void 組織の閉鎖前の招待は解除後も承諾できない() throws Exception {
        Fixture fx = fixture();
        long oldInvite = membership(fx.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);
        archiveThenUnarchiveWithoutCleanup(fx.org().id());

        Result result = acceptInvite(fx.ta(), fx.team().slug(), oldInvite);

        assertThat(result.status()).as("閉鎖前の招待の承諾: %s", result).isEqualTo(404);
        assertThat(result.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(oldInvite)).as("ACTIVE にしない").isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に閉鎖前の招待が片付く", () -> statusOf(oldInvite) == null);
        assertThat(jobIdsOf(oldInvite, CANCELLED)).hasSize(1);
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 2・§4.5: 解除後に API で作った申請・招待は、閉鎖の世代が進んでいても承認・承諾できる（閉鎖前と見なさない）")
    void 解除後の申請と招待は承認と承諾ができる() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請: %s", applied).isEqualTo(201);
        long newApply = jdbc.queryForObject(
                "SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, fx.team().id(), fx.org().id());
        Result invited = invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug());
        assertThat(invited.status()).as("解除後の招待: %s", invited).isEqualTo(201);
        long newInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());

        Result approved = approve(fx.xa(), fx.org().slug(), newApply);
        assertThat(approved.status()).as("解除後の申請の承認: %s", approved).isEqualTo(200);
        Result accepted = acceptInvite(inviteTeam.admin(), inviteTeam.team().slug(), newInvite);
        assertThat(accepted.status()).as("解除後の招待の承諾: %s", accepted).isEqualTo(200);
        assertThat(statusOf(newApply)).isEqualTo("ACTIVE");
        assertThat(statusOf(newInvite)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）High 2・§4.5: チームのアーカイブ前の申請が、片付けの前に解除されて残っていても、承認は 404 で拒み"
            + "（ACTIVE にしない）、回復バッチもチームの閉鎖前の PENDING を候補に拾って片付ける")
    void チームの閉鎖前の申請は解除後も承認できない() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin other = extraTeam();
        long oldApply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long otherOld = membership(other.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        archiveTeamThenUnarchiveWithoutCleanup(fx.team().id());
        archiveTeamThenUnarchiveWithoutCleanup(other.team().id());

        Result result = approve(fx.xa(), fx.org().slug(), oldApply);
        assertThat(result.status()).as("閉鎖前の申請の承認: %s", result).isEqualTo(404);
        assertThat(statusOf(oldApply)).isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に片付く", () -> statusOf(oldApply) == null);

        // もう一方はイベントを伴わないので、回復バッチが拾う
        assertThat(statusOf(otherOld)).isEqualTo("PENDING");
        cleanupBatch.cleanUp();
        assertThat(statusOf(otherOld)).as("チームの閉鎖前の PENDING はバッチが片付ける").isNull();
        UUID job = singleJob(otherOld, CANCELLED);
        assertThat(jobRow(job).get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
    }

    // =====================================================================
    // 2-D 検分差し戻し（3巡目）D-3T: 他ドメインの tx からの解除・組織の解除・チームの復元
    // =====================================================================

    @Test
    @DisplayName("2-D D-3T 根治: 他ドメインの tx からのチームの解除（unarchiveTeamDeferringAffiliationCleanup）は、呼び出し元の tx で加盟に触れず、"
            + "コミット後に team ドメインが閉鎖前の PENDING だけを片付ける。解除後の申請は消さない")
    void 他ドメインからのチーム解除はコミット後に閉鎖前のPENDINGを片付ける() throws Exception {
        Fixture fx = fixture();
        OrgFx orgY = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long oldApply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        // 他ドメインの tx からのアーカイブ（片付けが走らなかった）
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            teamService.unarchiveTeamDeferringAffiliationCleanup(fx.team().id());
            Number pendingInTx = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = :id AND status = 'PENDING'")
                    .setParameter("id", fx.team().id()).getSingleResult();
            assertThat(pendingInTx.longValue()).as("呼び出し元の tx の中では加盟に触れない").isEqualTo(1L);
        });
        assertThat(teamArchived(fx.team().id())).as("解除はコミットされる").isFalse();

        awaitCondition("コミット後に閉鎖前の PENDING が消える", () -> statusOf(oldApply) == null);
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);

        Result applied = apply(fx.ta(), fx.team().slug(), orgY.slug());
        assertThat(applied.status()).as("解除後の申請: %s", applied).isEqualTo(201);
        Long newApply = jdbc.queryForObject(
                "SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, fx.team().id(), orgY.id());
        assertThat(cleanupService.cleanUpTeam(fx.team().id())).as("冪等・解除後の申請は消さない").isZero();
        assertThat(statusOf(newApply)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）・§4.5: 片付けの前に組織のアーカイブを解除すると、OrganizationReopenedEvent で"
            + "閉鎖前の PENDING がすぐに片付く（回復バッチを待たない）")
    void 組織の解除はイベントで閉鎖前のPENDINGを片付ける() throws Exception {
        Fixture fx = fixture();
        long oldApply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        // アーカイブ（片付けのイベントは走らなかった）
        inTxVoid(() -> em.createNativeQuery("UPDATE organizations SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", fx.org().id()).executeUpdate());

        organizationService.unarchiveOrganization(fx.org().id());

        awaitCondition("解除のコミット後に閉鎖前の PENDING が消える", () -> statusOf(oldApply) == null);
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
    }

    @Test
    @DisplayName("2-D 差し戻し（3巡目）・§4.5: 論理削除の片付けの前にチームを復元しても、削除前の加盟（PENDING・ACTIVE）と制限を復元しない")
    void チームの復元は削除前の加盟を戻さない() throws Exception {
        Fixture fx = fixture();
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);
        blockRestriction(fx.team().id(), keeper.id(), "TEAM_APPLY");
        // 論理削除（片付けのイベントは走らなかった）
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET deleted_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());

        teamService.restoreTeam(fx.team().id(), fx.ta());

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM teams WHERE id = ?", Boolean.class,
                fx.team().id())).as("復元はコミットされる").isTrue();
        assertThat(statusOf(apply)).isNull();
        assertThat(statusOf(active)).as("削除前の ACTIVE も戻さない").isNull();
        assertThat(restrictionCountOfTeam(fx.team().id())).isZero();
        assertThat(jobIdsOf(apply, CANCELLED)).hasSize(1);
    }

    @Test
    @DisplayName("CMP-260902-0059 R7/R9: 復元更新が失敗すると加盟・制限・取消outboxも巻き戻り、成功監査は残らない")
    void チーム復元の更新失敗は加盟片付けと通知予約も巻き戻す() throws Exception {
        Fixture fx = fixture();
        OrgFx keeper = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long apply = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(fx.team().id(), keeper.id(), "ACTIVE", "ORG_INVITE", null);
        blockRestriction(fx.team().id(), keeper.id(), "TEAM_APPLY");
        inTxVoid(() -> em.createNativeQuery("UPDATE teams SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", fx.team().id()).executeUpdate());
        // 専用Testcontainers DBの所有slugだけに復元UPDATE失敗を注入する。特権が要るTRIGGERやDB全体設定は使わない。
        String constraintName = "restore_failure_" + UUID.randomUUID().toString().replace("-", "");
        String ownedSlug = fx.team().slug().replace("'", "''");
        jdbc.execute("ALTER TABLE teams ADD CONSTRAINT " + constraintName
                + " CHECK (slug <> '" + ownedSlug + "' OR deleted_at IS NOT NULL)");
        try {
            assertThatThrownBy(() -> teamService.restoreTeam(fx.team().id(), fx.ta()))
                    .hasStackTraceContaining(constraintName);
        } finally {
            jdbc.execute("ALTER TABLE teams DROP CHECK " + constraintName);
        }
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM teams WHERE id = ?", Boolean.class,
                fx.team().id())).isTrue();
        assertThat(statusOf(apply)).isEqualTo("PENDING");
        assertThat(statusOf(active)).isEqualTo("ACTIVE");
        assertThat(restrictionCountOfTeam(fx.team().id())).isEqualTo(1);
        assertThat(jobIdsOf(apply, CANCELLED)).isEmpty();
        awaitCondition("復元失敗後の非同期処理が完了する", () -> eventPool.getActiveCount() == 0
                && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                + "AND event_type = 'TEAM_RESTORED'", Long.class, fx.team().id())).isZero();
    }

    // =====================================================================
    // 2-D 検分差し戻し（4巡目）: 閉鎖前の判定は時刻ではなく閉鎖の世代番号で行う（同じ秒の境界でも破れない）
    // =====================================================================

    /**
     * 加盟行の作成日時を「いま」（閉鎖の後）に書き換える。時刻の比較（作成日時 &lt; 閉鎖時刻）で判定する実装なら、
     * この行は閉鎖の後に作られた行に見えて素通りする。世代で判定する実装は、作成時点に記録した世代で閉鎖前と見分ける。
     * 作成・閉鎖・解除を同じ秒の中で行う境界（DATETIME の秒丸め・切り捨てで前後が逆転しうる）を、時刻に頼らず再現する。
     */
    private void stampCreatedAtNow(long membershipId) {
        inTxVoid(() -> em.createNativeQuery("UPDATE team_org_memberships SET created_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", membershipId).executeUpdate());
    }

    private long membershipIdOf(long teamId, long orgId) {
        return jdbc.queryForObject("SELECT id FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
    }

    private long generationOf(String table, long id) {
        return jdbc.queryForObject("SELECT closure_generation FROM " + table + " WHERE id = ?", Long.class, id);
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）: 組織の閉鎖の世代は、アーカイブ・論理削除の API の経路で +1 され、アーカイブ解除・復元では戻らない。"
            + "申請・招待の API は作成時点の組織・チームの世代を行に記録する")
    void 組織の閉鎖の世代はアーカイブで進み解除では戻らない() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        assertThat(generationOf("organizations", fx.org().id())).isZero();

        organizationService.archiveOrganization(fx.org().id());
        assertThat(generationOf("organizations", fx.org().id())).as("アーカイブで +1").isEqualTo(1L);
        organizationService.unarchiveOrganization(fx.org().id());
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM organizations WHERE id = ?", Boolean.class,
                fx.org().id())).as("解除済み").isTrue();
        assertThat(generationOf("organizations", fx.org().id())).as("解除では戻らない").isEqualTo(1L);

        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("解除後の申請: %s", applied).isEqualTo(201);
        Result invited = invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug());
        assertThat(invited.status()).as("解除後の招待: %s", invited).isEqualTo(201);
        for (long id : List.of(membershipIdOf(fx.team().id(), fx.org().id()),
                membershipIdOf(inviteTeam.team().id(), fx.org().id()))) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT org_closure_generation, team_closure_generation FROM team_org_memberships WHERE id = ?", id);
            assertThat(((Number) row.get("org_closure_generation")).longValue()).as("作成時点の組織の世代").isEqualTo(1L);
            assertThat(((Number) row.get("team_closure_generation")).longValue()).as("作成時点のチームの世代").isZero();
        }
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）・境界: API で作った申請の直後（同じ秒の中）に組織を閉鎖 → 片付けの前に解除しても、"
            + "承認は 404 TEAM_070 で拒み ACTIVE にせず、ロールバックの後に片付けて回復バッチの後も戻らない。解除後の申請は承認できる")
    void 同じ秒の境界でも組織の閉鎖前の申請は承認できない() throws Exception {
        Fixture fx = fixture();
        Result applied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(applied.status()).as("申請: %s", applied).isEqualTo(201);
        long oldApply = membershipIdOf(fx.team().id(), fx.org().id());
        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        stampCreatedAtNow(oldApply);

        Result result = approve(fx.xa(), fx.org().slug(), oldApply);

        assertThat(result.status()).as("閉鎖前の申請の承認: %s", result).isEqualTo(404);
        assertThat(result.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(oldApply)).as("ACTIVE にしない").isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に閉鎖前の申請が片付く", () -> statusOf(oldApply) == null);
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
        cleanupBatch.cleanUp();
        assertThat(rowCount(fx.team().id(), fx.org().id())).as("回復バッチの後も行は戻らない").isZero();

        Result reapplied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(reapplied.status()).as("解除後の申請: %s", reapplied).isEqualTo(201);
        long newApply = membershipIdOf(fx.team().id(), fx.org().id());
        Result approved = approve(fx.xa(), fx.org().slug(), newApply);
        assertThat(approved.status()).as("解除後の申請の承認: %s", approved).isEqualTo(200);
        assertThat(statusOf(newApply)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）・境界: API で作った招待の直後（同じ秒の中）に組織を閉鎖 → 片付けの前に解除しても、"
            + "承諾は 404 TEAM_070 で拒み ACTIVE にせず、ロールバックの後に片付ける。解除後の招待は承諾できる")
    void 同じ秒の境界でも組織の閉鎖前の招待は承諾できない() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        Result invited = invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug());
        assertThat(invited.status()).as("招待: %s", invited).isEqualTo(201);
        long oldInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());
        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        stampCreatedAtNow(oldInvite);

        Result result = acceptInvite(inviteTeam.admin(), inviteTeam.team().slug(), oldInvite);

        assertThat(result.status()).as("閉鎖前の招待の承諾: %s", result).isEqualTo(404);
        assertThat(result.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(oldInvite)).as("ACTIVE にしない").isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に閉鎖前の招待が片付く", () -> statusOf(oldInvite) == null);
        assertThat(jobIdsOf(oldInvite, CANCELLED)).hasSize(1);
        cleanupBatch.cleanUp();
        assertThat(rowCount(inviteTeam.team().id(), fx.org().id())).as("回復バッチの後も行は戻らない").isZero();

        Result reinvited = invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug());
        assertThat(reinvited.status()).as("解除後の招待: %s", reinvited).isEqualTo(201);
        long newInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());
        Result accepted = acceptInvite(inviteTeam.admin(), inviteTeam.team().slug(), newInvite);
        assertThat(accepted.status()).as("解除後の招待の承諾: %s", accepted).isEqualTo(200);
        assertThat(statusOf(newInvite)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）・境界: API で作った申請・招待の直後（同じ秒の中）に組織を閉鎖 → 片付けの前に解除しても、"
            + "片付け（イベントと同じ入口）と回復バッチは閉鎖前の行を消し、解除後の申請・招待は残す")
    void 同じ秒の境界でも組織の閉鎖前のPENDINGは片付けと回復バッチで消える() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin applyTeam = extraTeam();
        TeamWithAdmin inviteTeam = extraTeam();
        TeamWithAdmin laterTeam = extraTeam();
        OrgFx orgB = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long orgBAdmin = inTx(() -> {
            long u = newUser();
            makeOrgAdmin(u, orgB.id());
            return u;
        });

        // 組織 X: 片付けの入口（cleanUpOrganization）で確かめる
        assertThat(apply(applyTeam.admin(), applyTeam.team().slug(), fx.org().slug()).status()).isEqualTo(201);
        assertThat(invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug()).status()).isEqualTo(201);
        long oldApply = membershipIdOf(applyTeam.team().id(), fx.org().id());
        long oldInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());
        // 組織 B: 回復バッチで確かめる
        assertThat(apply(applyTeam.admin(), applyTeam.team().slug(), orgB.slug()).status()).isEqualTo(201);
        assertThat(invite(orgBAdmin, orgB.slug(), inviteTeam.team().slug()).status()).isEqualTo(201);
        long oldApplyB = membershipIdOf(applyTeam.team().id(), orgB.id());
        long oldInviteB = membershipIdOf(inviteTeam.team().id(), orgB.id());

        archiveThenUnarchiveWithoutCleanup(fx.org().id());
        archiveThenUnarchiveWithoutCleanup(orgB.id());
        for (long id : List.of(oldApply, oldInvite, oldApplyB, oldInviteB)) {
            stampCreatedAtNow(id);
        }
        assertThat(apply(laterTeam.admin(), laterTeam.team().slug(), fx.org().slug()).status()).isEqualTo(201);
        assertThat(invite(orgBAdmin, orgB.slug(), laterTeam.team().slug()).status()).isEqualTo(201);
        long laterApply = membershipIdOf(laterTeam.team().id(), fx.org().id());
        long laterInviteB = membershipIdOf(laterTeam.team().id(), orgB.id());

        assertThat(cleanupService.cleanUpOrganization(fx.org().id())).as("閉鎖前の2件だけを消す").isEqualTo(2);
        assertThat(statusOf(oldApply)).isNull();
        assertThat(statusOf(oldInvite)).isNull();
        assertThat(statusOf(laterApply)).as("解除後の申請は残る").isEqualTo("PENDING");

        cleanupBatch.cleanUp();
        assertThat(statusOf(oldApplyB)).as("回復バッチが閉鎖前の申請を拾う").isNull();
        assertThat(statusOf(oldInviteB)).as("回復バッチが閉鎖前の招待を拾う").isNull();
        assertThat(jobIdsOf(oldApplyB, CANCELLED)).hasSize(1);
        assertThat(jobIdsOf(oldInviteB, CANCELLED)).hasSize(1);
        assertThat(statusOf(laterInviteB)).as("解除後の招待は残る").isEqualTo("PENDING");
        assertThat(statusOf(laterApply)).as("回復バッチも解除後の申請を消さない").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）・境界: API で作った申請・招待の直後（同じ秒の中）にチームを閉鎖 → 片付けの前に解除しても、"
            + "承認・承諾は 404 で拒み ACTIVE にせず、回復バッチも閉鎖前の行を消す。解除後の申請・招待は承認・承諾できる")
    void 同じ秒の境界でもチームの閉鎖前のPENDINGは承認承諾できず片付く() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin inviteTeam = extraTeam();
        TeamWithAdmin batchTeam = extraTeam();
        assertThat(apply(fx.ta(), fx.team().slug(), fx.org().slug()).status()).isEqualTo(201);
        assertThat(invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug()).status()).isEqualTo(201);
        assertThat(apply(batchTeam.admin(), batchTeam.team().slug(), fx.org().slug()).status()).isEqualTo(201);
        long oldApply = membershipIdOf(fx.team().id(), fx.org().id());
        long oldInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());
        long oldBatch = membershipIdOf(batchTeam.team().id(), fx.org().id());
        for (long teamId : List.of(fx.team().id(), inviteTeam.team().id(), batchTeam.team().id())) {
            archiveTeamThenUnarchiveWithoutCleanup(teamId);
        }
        for (long id : List.of(oldApply, oldInvite, oldBatch)) {
            stampCreatedAtNow(id);
        }

        Result approved = approve(fx.xa(), fx.org().slug(), oldApply);
        assertThat(approved.status()).as("チームの閉鎖前の申請の承認: %s", approved).isEqualTo(404);
        assertThat(approved.errorCode()).isEqualTo("TEAM_070");
        Result accepted = acceptInvite(inviteTeam.admin(), inviteTeam.team().slug(), oldInvite);
        assertThat(accepted.status()).as("チームの閉鎖前の招待の承諾: %s", accepted).isEqualTo(404);
        assertThat(accepted.errorCode()).isEqualTo("TEAM_070");
        assertThat(statusOf(oldApply)).isNotEqualTo("ACTIVE");
        assertThat(statusOf(oldInvite)).isNotEqualTo("ACTIVE");
        awaitCondition("ロールバックの後に閉鎖前の申請・招待が片付く",
                () -> statusOf(oldApply) == null && statusOf(oldInvite) == null);

        assertThat(statusOf(oldBatch)).as("イベントを伴わない行は回復バッチまで残る").isEqualTo("PENDING");
        cleanupBatch.cleanUp();
        assertThat(statusOf(oldBatch)).as("回復バッチがチームの閉鎖前の申請を拾う").isNull();
        UUID job = singleJob(oldBatch, CANCELLED);
        assertThat(jobRow(job).get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");

        Result reapplied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(reapplied.status()).as("解除後の申請: %s", reapplied).isEqualTo(201);
        long newApply = membershipIdOf(fx.team().id(), fx.org().id());
        assertThat(approve(fx.xa(), fx.org().slug(), newApply).status()).as("解除後の申請の承認").isEqualTo(200);
        Result reinvited = invite(fx.xa(), fx.org().slug(), inviteTeam.team().slug());
        assertThat(reinvited.status()).as("解除後の招待: %s", reinvited).isEqualTo(201);
        long newInvite = membershipIdOf(inviteTeam.team().id(), fx.org().id());
        assertThat(acceptInvite(inviteTeam.admin(), inviteTeam.team().slug(), newInvite).status())
                .as("解除後の招待の承諾").isEqualTo(200);
        assertThat(statusOf(newApply)).isEqualTo("ACTIVE");
        assertThat(statusOf(newInvite)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("2-D 差し戻し（4巡目）・境界: 他ドメインの tx からの「アーカイブ → 解除」を API で作った申請の直後に1つの tx で行っても"
            + "（片付けはどちらもコミット後）、チームの世代が進んで閉鎖前の申請は片付き ACTIVE にならない")
    void 同じtxでアーカイブと解除をしても閉鎖前の申請は片付く() throws Exception {
        Fixture fx = fixture();
        assertThat(apply(fx.ta(), fx.team().slug(), fx.org().slug()).status()).isEqualTo(201);
        long oldApply = membershipIdOf(fx.team().id(), fx.org().id());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            teamService.archiveTeamDeferringAffiliationCleanup(fx.team().id());
            teamService.unarchiveTeamDeferringAffiliationCleanup(fx.team().id());
        });
        assertThat(teamArchived(fx.team().id())).isFalse();
        assertThat(generationOf("teams", fx.team().id())).as("アーカイブで +1・解除で戻らない").isEqualTo(1L);

        awaitCondition("コミット後に閉鎖前の申請が片付く", () -> statusOf(oldApply) == null);
        assertThat(jobIdsOf(oldApply, CANCELLED)).hasSize(1);
        Result reapplied = apply(fx.ta(), fx.team().slug(), fx.org().slug());
        assertThat(reapplied.status()).as("解除後の申請: %s", reapplied).isEqualTo(201);
        assertThat(cleanupService.cleanUpTeam(fx.team().id())).as("解除後の申請は消さない").isZero();
    }
}
