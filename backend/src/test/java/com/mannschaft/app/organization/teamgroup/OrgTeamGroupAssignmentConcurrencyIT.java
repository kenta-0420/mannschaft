package com.mannschaft.app.organization.teamgroup;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 部隊 4-B — 並行するグループ削除と割当の競合（実コミットを要する IT。テストトランザクションを使わない）。
 *
 * <p>グループの削除は組織行を {@code FOR UPDATE} でロックしたまま行い、コミット後に非同期のリスナーが
 * 所属行の {@code group_id} を未分類へ戻す。割当が「グループは生きている」と確認した直後に削除がコミットされ、
 * リスナーの付け替えが済んだ後に割当が書き込むと、削除済みグループを指す {@code group_id} が残り続ける
 * （次の夜間バッチまで）。割当が同じ組織行のロックを取り、ロックの内側でグループの生存を確認することで、
 * 削除と割当は必ずどちらかが先に完了し、後の側は先の結果を見る。</p>
 *
 * <p>ここでは、削除の側を「ロックを握ったまま、削除済みの印を付けて、コミットを待っている」状態で止め、
 * その間に割当を投げる。割当は<b>待たされ</b>（先に答えを返さない）、削除がコミットされた後に
 * 404 {@code ORG_064} を返し、何も書き込まない。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 4-B 割当と並行するグループ削除の競合")
class OrgTeamGroupAssignmentConcurrencyIT extends AbstractOrgTeamGroupAssignmentIT {

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    private Long orgId;
    private String slug;
    private UUID groupId;
    private final List<TeamOrgMembershipEntity> memberships = new ArrayList<>();
    private final List<String> teamSlugs = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            OrganizationEntity org = newOrg(true);
            orgId = org.getId();
            slug = org.getSlug();
            seedOrgPerson(AXA, orgId, "ADMIN");
            OrgTeamGroupEntity group = newGroup(orgId, "競合する班", 0);
            groupId = group.getId();
            for (int i = 0; i < 2; i++) {
                TeamOrgMembershipEntity m = activeTeam(orgId, null);
                memberships.add(m);
                teamIds.add(m.getTeamId());
                teamSlugs.add(teamRepository.findById(m.getTeamId()).orElseThrow().getSlug());
            }
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_logs WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM team_org_memberships WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM org_team_groups WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND organization_id = ?", AXA, orgId);
        jdbc.update("DELETE FROM memberships WHERE user_id = ? AND scope_type = 'ORGANIZATION' AND scope_id = ?",
                AXA, orgId);
        for (Long teamId : teamIds) {
            jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @DisplayName("一括割当: 削除が組織行のロックを握っている間は待たされ、削除のコミット後は 404 ORG_064 で、何も書き込まない")
    void bulkAssign_waitsForConcurrentDelete_thenSeesTheDeletedGroup() throws Exception {
        MvcResult result = runWhileDeleteIsInFlight(
                () -> assignBulk(AXA, slug, groupId, teamSlugs).andReturn());

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("ORG_064");
        assertThat(assignedTo(groupId)).as("削除済みグループを指す割当を作らない").isZero();
    }

    @Test
    @DisplayName("単体割当: 削除が組織行のロックを握っている間は待たされ、削除のコミット後は 404 ORG_064 で、何も書き込まない")
    void singleAssign_waitsForConcurrentDelete_thenSeesTheDeletedGroup() throws Exception {
        MvcResult result = runWhileDeleteIsInFlight(
                () -> assignOne(AXA, slug, teamSlugs.get(0), groupId).andReturn());

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("ORG_064");
        assertThat(assignedTo(groupId)).isZero();
    }

    @Test
    @DisplayName("割当が先にコミットされた場合は、その後の削除のリスナーが付け替え、削除済みグループを指す行は残らない")
    void assignThenDelete_listenerClearsTheAssignment() throws Exception {
        assignBulk(AXA, slug, groupId, teamSlugs).andReturn();
        assertThat(assignedTo(groupId)).isEqualTo(2);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/organizations/{slug}/team-groups/{id}", slug, groupId.toString())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user(AXA.toString())))
                .andReturn();

        long deadline = System.currentTimeMillis() + 20_000;
        long remaining;
        do {
            remaining = assignedTo(groupId);
            if (remaining == 0) {
                break;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        assertThat(remaining).isZero();
    }

    // ───────── 内部 ─────────

    /**
     * 削除側のトランザクション（組織行を FOR UPDATE → グループを削除済みにする → コミット待ち）を止めたまま
     * 割当を投げ、割当が待たされることを確かめてから、削除をコミットして割当の結果を返す。
     */
    private MvcResult runWhileDeleteIsInFlight(Callable<MvcResult> assignment) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch deleteHoldsLock = new CountDownLatch(1);
        CountDownLatch commitDelete = new CountDownLatch(1);
        try {
            Future<?> deleter = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM organizations WHERE id = ? FOR UPDATE", Long.class, orgId);
                jdbc.update("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = ?",
                        (Object) uuidBytes(groupId));
                deleteHoldsLock.countDown();
                try {
                    if (!commitDelete.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("削除のコミット指示が来ない");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(deleteHoldsLock.await(30, TimeUnit.SECONDS)).as("削除側がロックを取った").isTrue();

            Future<MvcResult> assigner = pool.submit(assignment);
            assertThatThrownBy(() -> assigner.get(1500, TimeUnit.MILLISECONDS))
                    .as("削除が組織行のロックを握っている間、割当は答えを返さず待たされる")
                    .isInstanceOf(TimeoutException.class);

            commitDelete.countDown();
            deleter.get(30, TimeUnit.SECONDS);
            return assigner.get(30, TimeUnit.SECONDS);
        } finally {
            commitDelete.countDown();
            pool.shutdownNow();
        }
    }

    private long assignedTo(UUID id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE organization_id = ? AND group_id = ?",
                Long.class, orgId, uuidBytes(id));
        return n == null ? 0 : n;
    }

    private static byte[] uuidBytes(UUID id) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(new byte[16]);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
