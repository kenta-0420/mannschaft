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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 4-A — 実コミットを要する IT（テストトランザクションを使わない）。
 *
 * <ul>
 *   <li>AC-F08（処理後）: グループを削除すると、AFTER_COMMIT・@Async のリスナーが
 *       所属行の {@code group_id} を DB 上でも NULL に戻す。</li>
 *   <li>AC-G135: 同名グループを並行で作成すると、一方だけ成功し、もう一方は 500 ではなく 409 {@code ORG_065}。</li>
 * </ul>
 *
 * <p>コミットされたデータは各テストの {@code @AfterEach} で自分の行だけ削除する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 4-A リスナー（コミット後・非同期）と並行作成")
class OrgTeamGroupAsyncAndConcurrencyIT extends AbstractOrgTeamGroupIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    private Long orgId;
    private String slug;
    private final List<Long> teamIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            OrganizationEntity org = newOrg(true);
            orgId = org.getId();
            slug = org.getSlug();
            seedOrgPerson(XA, orgId, "ADMIN");
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_logs WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM team_org_memberships WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM org_team_groups WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND organization_id = ?", XA, orgId);
        jdbc.update("DELETE FROM memberships WHERE user_id = ? AND scope_type = 'ORGANIZATION' AND scope_id = ?", XA, orgId);
        for (Long teamId : teamIds) {
            jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @DisplayName("AC-F08: 削除後、リスナーが非同期に走り、所属行の group_id が DB 上でも NULL に戻る（他グループの行は触らない）")
    void delete_listenerClearsGroupIdAfterCommit() throws Exception {
        UUID[] ids = new UUID[2];
        tx.executeWithoutResult(status -> {
            OrgTeamGroupEntity target = newGroup(orgId, "削除される", 0);
            OrgTeamGroupEntity other = newGroup(orgId, "残る", 1);
            ids[0] = target.getId();
            ids[1] = other.getId();
            teamIds.add(newMembership(orgId, TeamOrgMembershipEntity.Status.ACTIVE, target.getId()).getTeamId());
            teamIds.add(newMembership(orgId, TeamOrgMembershipEntity.Status.ACTIVE, target.getId()).getTeamId());
            teamIds.add(newMembership(orgId, TeamOrgMembershipEntity.Status.PENDING, target.getId()).getTeamId());
            teamIds.add(newMembership(orgId, TeamOrgMembershipEntity.Status.ACTIVE, other.getId()).getTeamId());
        });

        mockMvc.perform(delete(BASE + "/{id}", slug, ids[0].toString()).with(user(XA.toString())))
                .andExpect(status().isNoContent());

        long deadline = System.currentTimeMillis() + 20_000;
        long remaining;
        do {
            remaining = countByGroup(ids[0]);
            if (remaining == 0) {
                break;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);

        assertThat(remaining).as("削除したグループを指す group_id が残らない（リスナー処理後）").isZero();
        assertThat(countByGroup(ids[1])).as("別グループの割当は変わらない").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-G135: 同名グループを並行で作成すると、一方だけ 201 で、もう一方は 500 ではなく 409 ORG_065。生存行は1件")
    void concurrentCreateSameName_oneCreatedOneConflict() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<MvcResult> task = () -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post(BASE, slug).with(user(XA.toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"並行同名\"}")).andReturn();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                statuses.add(r.getResponse().getStatus());
                bodies.add(r.getResponse().getContentAsString());
            }
            assertThat(statuses).as("bodies=%s", bodies).containsExactlyInAnyOrder(201, 409);
            assertThat(bodies.stream().filter(b -> b.contains("ORG_065")).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Long live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ? AND deleted_at IS NULL", Long.class, orgId);
        assertThat(live).isEqualTo(1L);
    }

    private long countByGroup(UUID groupId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE organization_id = ? AND group_id = ?",
                Long.class, orgId, uuidBytes(groupId));
        return n == null ? 0 : n;
    }

    private static byte[] uuidBytes(UUID id) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(new byte[16]);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
