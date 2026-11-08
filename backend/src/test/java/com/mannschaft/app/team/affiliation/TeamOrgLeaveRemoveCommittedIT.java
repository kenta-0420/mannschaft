package com.mannschaft.app.team.affiliation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 2-D — 離脱・除名の通知（enqueue と Worker による配信）の統合テスト（試練）。
 *
 * <p>{@link TeamOrgLeaveRemoveIT} は {@code @Transactional} でロールバックするため、Worker による配信
 * （別トランザクションで受信者を解決して通知行を作る）は検証できない。本クラスはテストメソッドにトランザクションを張らず、
 * フィクスチャはトランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。</p>
 *
 * <ul>
 *   <li>AC-E09・G117h: 離脱すると、組織の ADMIN 全員に {@code TEAM_ORG_MEMBERSHIP_LEFT} が届き、
 *       リンクは member-teams を開く（DEPUTY・MEMBER・チーム側の人には届かない）</li>
 *   <li>AC-E10・G117i: 除名すると、チームの加盟操作者（TA・TG）に {@code TEAM_ORG_MEMBERSHIP_REMOVED} が届き、
 *       TM・TD には届かない。理由欄は無い</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-D 離脱・除名の通知（コミットを伴う検証）")
class TeamOrgLeaveRemoveCommittedIT extends TeamOrgLifecycleCommittedItSupport {

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    @Test
    @DisplayName("AC-E09・G117h 離脱の通知は組織 ADMIN 全員（XA・XA2）にだけ届き、member-teams を開く。DEPUTY・チーム側の人には届かない")
    void 離脱の通知は組織ADMIN全員に届く() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", null);

        assertThat(leave(fx.ta(), fx.team().slug(), fx.org().slug()).status()).isEqualTo(204);

        UUID jobId = singleJob(id, "TEAM_ORG_MEMBERSHIP_LEFT");
        Map<String, Object> job = jobRow(jobId);
        assertThat(job.get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
        assertThat(job.get("action_url")).isEqualTo("/organizations/" + fx.org().slug() + "/member-teams");
        assertThat(((Number) job.get("organization_id")).longValue()).isEqualTo(fx.org().id());
        deliver(jobId);

        List<Map<String, Object>> rows = notificationsOf("TEAM_ORG_MEMBERSHIP_LEFT",
                fx.xa(), fx.xa2(), fx.xd(), fx.ta(), fx.tg(), fx.tm(), fx.td());
        assertThat(userIdsOf(rows)).as("組織 ADMIN の XA・XA2 にだけ1件ずつ届く").containsExactlyInAnyOrder(fx.xa(), fx.xa2());
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/organizations/" + fx.org().slug() + "/member-teams");
        assertThat(rows).extracting(r -> r.get("source_type")).containsOnly("TEAM_ORG_MEMBERSHIP");
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.team().name()).contains(fx.org().name()));
    }

    @Test
    @DisplayName("AC-E09 通知は離脱した組織の ADMIN にだけ届く（同じチームが加盟している別の組織の ADMIN には届かない）")
    void 離脱の通知は別組織のADMINには届かない() throws Exception {
        Fixture fx = fixture();
        OrgFx orgY = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long ya = inTx(() -> {
            long u = newUser();
            makeOrgAdmin(u, orgY.id());
            return u;
        });
        long idX = membership(fx.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", null);
        membership(fx.team().id(), orgY.id(), "ACTIVE", "ORG_INVITE", null);

        assertThat(leave(fx.tg(), fx.team().slug(), fx.org().slug()).status())
                .as("権限グループで付与された TG の離脱でも通知される").isEqualTo(204);

        deliver(singleJob(idX, "TEAM_ORG_MEMBERSHIP_LEFT"));
        assertThat(userIdsOf(notificationsOf("TEAM_ORG_MEMBERSHIP_LEFT", fx.xa(), fx.xa2(), ya)))
                .containsExactlyInAnyOrder(fx.xa(), fx.xa2());
        assertThat(statusOf(idX)).isNull();
    }

    @Test
    @DisplayName("AC-E10・G117i 除名の通知はチームの加盟操作者（TA・TG）にだけ届き、affiliations を開く。TM・TD・組織 ADMIN には届かない。理由欄は無い")
    void 除名の通知は加盟操作者に届く() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", null);

        // 除名に理由欄は無い: body なしで 204
        assertThat(remove(fx.xa(), fx.org().slug(), fx.team().slug()).status()).isEqualTo(204);

        UUID jobId = singleJob(id, "TEAM_ORG_MEMBERSHIP_REMOVED");
        Map<String, Object> job = jobRow(jobId);
        assertThat(job.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
        assertThat(job.get("action_url")).isEqualTo("/teams/" + fx.team().slug() + "/affiliations");
        assertThat(((Number) job.get("organization_id")).longValue()).isEqualTo(fx.org().id());
        deliver(jobId);

        List<Map<String, Object>> rows = notificationsOf("TEAM_ORG_MEMBERSHIP_REMOVED",
                fx.ta(), fx.tg(), fx.tm(), fx.td(), fx.xa(), fx.xa2(), fx.xd());
        assertThat(userIdsOf(rows)).as("TA と TG にだけ1件ずつ届く（TM・TD・組織側には届かない）")
                .containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/teams/" + fx.team().slug() + "/affiliations");
        assertThat(rows).extracting(r -> r.get("source_type")).containsOnly("TEAM_ORG_MEMBERSHIP");
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.team().name()).contains(fx.org().name()));
    }

    @Test
    @DisplayName("AC-E10 除名・離脱に失敗（権限なし・未加盟）したときは、通知ジョブを作らない")
    void 失敗した操作は通知を作らない() throws Exception {
        Fixture fx = fixture();
        long id = membership(fx.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", null);

        assertThat(remove(fx.xd(), fx.org().slug(), fx.team().slug()).status()).isEqualTo(403);
        assertThat(leave(fx.td(), fx.team().slug(), fx.org().slug()).status()).isEqualTo(403);

        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(jobIdsOf(id, "TEAM_ORG_MEMBERSHIP_LEFT")).isEmpty();
        assertThat(jobIdsOf(id, "TEAM_ORG_MEMBERSHIP_REMOVED")).isEmpty();
    }
}
