package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 7-A — 加盟の夜間バッチ3本（§6.8）の統合テスト（試練）。
 *
 * <ul>
 *   <li>{@code team-org-pending-expiry}（03:10）: 60日（{@code mannschaft.affiliation.pending-ttl-days}）応答の無い PENDING を
 *       消し、起こした側へ {@code TEAM_ORG_PENDING_EXPIRED} を outbox で通知する。期限切れの COOLDOWN 制限も消す</li>
 *   <li>{@code team-org-group-orphan-repair}（03:20）: 削除済みグループを指す {@code group_id} を NULL に戻す（§7.3 の修復）</li>
 *   <li>{@code team-org-lifecycle-cleanup}（03:30）: 2-D の回復バッチ（ここでは手動起動 API での登録と 200 を確かめる）</li>
 * </ul>
 *
 * <p>実機E2E と同じく、DB の日時列を過去へずらしてから手動起動 API（{@code POST /api/v1/system-admin/batch/{name}/trigger?sync=true}・
 * SYSTEM_ADMIN）で起動する（§4.6・§16.0）。バッチは自分のトランザクションでコミットするので、フィクスチャはトランザクションを
 * 分けてコミットし、{@link #cleanUp()} で物理削除する（{@link TeamOrgLifecycleCommittedItSupport} の作法）。</p>
 *
 * <ul>
 *   <li>AC-E04: 60日応答の無い PENDING 申請が消え、TA に期限切れの通知が届き、同時申請数が1減る</li>
 *   <li>AC-G117f: {@code TEAM_ORG_PENDING_EXPIRED} は起こした側へ（申請＝チームの加盟操作者、招待＝組織 ADMIN）</li>
 *   <li>AC-G103i: 期限切れは監査ログ {@code TEAM_ORG_PENDING_EXPIRED} を1行残す</li>
 *   <li>AC-G108: 期限切れバッチは期限切れの COOLDOWN だけを消し、有効な COOLDOWN と BLOCK は残す</li>
 *   <li>AC-F11: 付け替えリスナーの失敗で削除済みグループを指したまま残った {@code group_id} が、修復バッチで NULL になる</li>
 *   <li>AC-E06: 3本が一覧に出て、{@code trigger?sync=true} で 200</li>
 *   <li>AC-G132: 対象0件でも 200 で正常に終わり、2回続けて実行しても結果が同じ（冪等）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 7-A 加盟の夜間バッチ（期限切れ・孤児 group_id の修復・片付け）")
class TeamOrgAffiliationBatchesCommittedIT extends TeamOrgLifecycleCommittedItSupport {

    private static final String EXPIRY = "team-org-pending-expiry";
    private static final String ORPHAN_REPAIR = "team-org-group-orphan-repair";
    private static final String LIFECYCLE_CLEANUP = "team-org-lifecycle-cleanup";
    private static final String EXPIRED = "TEAM_ORG_PENDING_EXPIRED";
    private static final String CANCELLED = "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM";

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-E04・G117f・G103i 期限切れ
    // =====================================================================

    @Test
    @DisplayName("AC-E04・G117f・G103i 61日前の PENDING 申請は期限切れバッチで消え、チームの加盟操作者（TA・TG）にだけ "
            + "TEAM_ORG_PENDING_EXPIRED が届き（リンクは /teams/{t}/affiliations?view=applications）、同時申請数が1減り、監査が1行残る。"
            + "59日前の申請と、古い ACTIVE は残る")
    void 期限切れの申請が消えてチームへ通知される() throws Exception {
        Fixture fx = fixture();
        OrgFx otherOrg = inTx(() -> newOrg(true, false, "OFF", "PUBLIC"));
        long expired = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long fresh = membership(fx.team().id(), otherOrg.id(), "PENDING", "TEAM_APPLY", null);
        TeamWithAdmin activeTeam = extraTeam();
        long oldActive = membership(activeTeam.team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);
        shiftInvitedAtDaysAgo(expired, 61);
        shiftInvitedAtDaysAgo(fresh, 59);
        shiftInvitedAtDaysAgo(oldActive, 400);
        long applyingBefore = pendingApplicationCount(fx.team().id());

        assertThat(trigger(EXPIRY)).isEqualTo(200);

        assertThat(statusOf(expired)).as("60日を過ぎた PENDING は消える").isNull();
        assertThat(statusOf(fresh)).as("60日に満たない PENDING は残る").isEqualTo("PENDING");
        assertThat(statusOf(oldActive)).as("ACTIVE は期限切れの対象外").isEqualTo("ACTIVE");
        assertThat(pendingApplicationCount(fx.team().id())).as("同時申請数が1減る（E04）")
                .isEqualTo(applyingBefore - 1);

        UUID job = singleJob(expired, EXPIRED);
        Map<String, Object> row = jobRow(job);
        assertThat(row.get("scope_type")).as("申請を起こした側＝チームの加盟操作者").isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(row.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
        assertThat(row.get("action_url")).isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        assertThat(((Number) row.get("organization_id")).longValue()).isEqualTo(fx.org().id());
        deliver(job);
        List<Map<String, Object>> received = notificationsOf(EXPIRED, fx.ta(), fx.tg(), fx.tm(), fx.xa(), fx.xa2());
        assertThat(userIdsOf(received)).containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(received).allSatisfy(n -> {
            assertThat(n.get("source_type")).isEqualTo("TEAM_ORG_MEMBERSHIP");
            assertThat(n.get("action_url"))
                    .isEqualTo("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        });
        assertThat(jobIdsOf(fresh, EXPIRED)).isEmpty();
        assertThat(jobIdsOf(oldActive, EXPIRED)).isEmpty();

        List<Map<String, Object>> audits = expiredAudits(fx.team().id());
        assertThat(audits).as("期限切れの監査は1行（G103i）").hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit.get("user_id")).as("システムの遷移なので操作者は無い").isNull();
        assertThat(((Number) audit.get("organization_id")).longValue()).isEqualTo(fx.org().id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit.get("metadata")));
        assertThat(metadata.path("membership_id").asLong()).isEqualTo(expired);
        assertThat(metadata.path("direction").asText()).isEqualTo("TEAM_APPLY");
    }

    @Test
    @DisplayName("AC-G117f 61日前の PENDING 招待は期限切れバッチで消え、招待を起こした組織の ADMIN（XA・XA2）にだけ "
            + "TEAM_ORG_PENDING_EXPIRED が届く（リンクは /organizations/{o}/member-teams?view=invites）")
    void 期限切れの招待が消えて組織へ通知される() throws Exception {
        Fixture fx = fixture();
        long expired = membership(fx.team().id(), fx.org().id(), "PENDING", "ORG_INVITE", null);
        shiftInvitedAtDaysAgo(expired, 61);

        assertThat(trigger(EXPIRY)).isEqualTo(200);

        assertThat(statusOf(expired)).isNull();
        UUID job = singleJob(expired, EXPIRED);
        Map<String, Object> row = jobRow(job);
        assertThat(row.get("scope_type")).as("招待を起こした側＝組織 ADMIN").isEqualTo("ORGANIZATION_ADMINS");
        assertThat(row.get("scope_ref")).isEqualTo(String.valueOf(fx.org().id()));
        assertThat(row.get("action_url")).isEqualTo("/organizations/" + fx.org().slug() + "/member-teams?view=invites");
        deliver(job);
        assertThat(userIdsOf(notificationsOf(EXPIRED, fx.ta(), fx.tg(), fx.xa(), fx.xa2(), fx.xd())))
                .containsExactlyInAnyOrder(fx.xa(), fx.xa2());
        assertThat(expiredAudits(fx.team().id())).hasSize(1);
    }

    @Test
    @DisplayName("AC-G108 期限切れバッチは期限切れの COOLDOWN だけを消し、有効な COOLDOWN と BLOCK は残す")
    void 期限切れのCOOLDOWNだけが消える() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin blockTeam = extraTeam();
        restriction(fx.team().id(), fx.org().id(), "TEAM_APPLY", "COOLDOWN", "UTC_TIMESTAMP() - INTERVAL 1 HOUR");
        restriction(fx.team().id(), fx.org().id(), "ORG_INVITE", "COOLDOWN", "UTC_TIMESTAMP() + INTERVAL 1 DAY");
        restriction(blockTeam.team().id(), fx.org().id(), "TEAM_APPLY", "BLOCK", "NULL");

        assertThat(trigger(EXPIRY)).isEqualTo(200);

        assertThat(restrictionKinds(fx.org().id())).containsExactlyInAnyOrder(
                "ORG_INVITE:COOLDOWN", "TEAM_APPLY:BLOCK");
    }

    @Test
    @DisplayName("§6.8 並行: 期限切れの判定の後に承認がその行を先にロックして ACTIVE にしたなら、期限切れバッチはロックを待ち、"
            + "状態を再確認して ACTIVE の行を消さず、期限切れも通知しない")
    void 承認済みの行は期限切れで消さない() throws Exception {
        Fixture fx = fixture();
        long pending = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        shiftInvitedAtDaysAgo(pending, 61);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 承認と同じく加盟行を FOR UPDATE で取り、ACTIVE にしたまま保留する
            HeldTx approval = new HeldTx(executor, () -> {
                em.createNativeQuery("SELECT id FROM team_org_memberships WHERE id = :id FOR UPDATE")
                        .setParameter("id", pending).getResultList();
                em.createNativeQuery("UPDATE team_org_memberships SET status = 'ACTIVE', "
                                + "responded_at = UTC_TIMESTAMP() WHERE id = :id")
                        .setParameter("id", pending).executeUpdate();
            });
            Future<Integer> batch = executor.submit(() -> trigger(EXPIRY));
            Thread.sleep(1_000);
            assertThat(batch.isDone()).as("バッチは加盟行のロックを待つ").isFalse();
            approval.commit();
            assertThat(batch.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusOf(pending)).as("承認済みの行は消さない").isEqualTo("ACTIVE");
        assertThat(jobIdsOf(pending, EXPIRED)).isEmpty();
        assertThat(expiredAudits(fx.team().id())).isEmpty();
    }

    // =====================================================================
    // AC-F11 孤児 group_id の修復
    // =====================================================================

    @Test
    @DisplayName("AC-F11 削除済みグループを指したまま残った group_id（ACTIVE・PENDING とも）は修復バッチで NULL に戻り、"
            + "生存グループの割当は変わらない")
    void 削除済みグループを指すgroupIdがNULLに戻る() throws Exception {
        Fixture fx = fixture();
        TeamWithAdmin pendingTeam = extraTeam();
        TeamWithAdmin aliveTeam = extraTeam();
        UUID deletedGroup = inTx(() -> newGroup(fx.org().id(), "削除済み", true));
        UUID aliveGroup = inTx(() -> newGroup(fx.org().id(), "生存", false));
        long orphanActive = membership(fx.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", deletedGroup);
        long orphanPending = membership(pendingTeam.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", deletedGroup);
        long assigned = membership(aliveTeam.team().id(), fx.org().id(), "ACTIVE", "TEAM_APPLY", aliveGroup);

        assertThat(trigger(ORPHAN_REPAIR)).isEqualTo(200);

        assertThat(groupIdOf(orphanActive)).as("削除済みグループを指す ACTIVE は未分類へ").isNull();
        assertThat(groupIdOf(orphanPending)).as("削除済みグループを指す PENDING も未分類へ").isNull();
        assertThat(groupIdOf(assigned)).as("生存グループの割当は変えない").isEqualTo(aliveGroup);
        assertThat(statusOf(orphanActive)).isEqualTo("ACTIVE");
        assertThat(statusOf(orphanPending)).isEqualTo("PENDING");

        // 冪等（G132）
        assertThat(trigger(ORPHAN_REPAIR)).isEqualTo(200);
        assertThat(groupIdOf(assigned)).isEqualTo(aliveGroup);
        assertThat(groupIdOf(orphanActive)).isNull();
    }

    // =====================================================================
    // AC-E06・G132 登録・0件・冪等
    // =====================================================================

    @Test
    @DisplayName("AC-E06 GET /api/v1/system-admin/batch の一覧に3本が出る")
    void 三本のバッチが一覧に出る() throws Exception {
        long admin = inTx(this::newUser);
        MvcResult result = mockMvc.perform(get("/api/v1/system-admin/batch")
                        .with(user(String.valueOf(admin)).roles("SYSTEM_ADMIN")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(result.getResponse().getContentAsString()).path("data")) {
            names.add(node.path("name").asText());
        }
        assertThat(names).contains(EXPIRY, ORPHAN_REPAIR, LIFECYCLE_CLEANUP);
    }

    @Test
    @DisplayName("AC-E06・G132 3本とも、自分の対象が0件でも trigger?sync=true で 200 を返し、2回続けて実行しても 200 で結果が変わらない")
    void 三本とも0件でも200で冪等() throws Exception {
        Fixture fx = fixture();
        long fresh = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        for (String name : List.of(EXPIRY, ORPHAN_REPAIR, LIFECYCLE_CLEANUP)) {
            assertThat(trigger(name)).as("%s の1回目", name).isEqualTo(200);
            assertThat(trigger(name)).as("%s の2回目", name).isEqualTo(200);
        }
        assertThat(statusOf(fresh)).as("対象外の行には触れない").isEqualTo("PENDING");
        assertThat(jobIdsOf(fresh, EXPIRED)).isEmpty();
        assertThat(jobIdsOf(fresh, CANCELLED)).isEmpty();
    }

    @Test
    @DisplayName("AC-G132 期限切れバッチを2回続けて実行しても、行は1度だけ消え、通知も監査も1件のまま（二重通知しない）")
    void 期限切れを2回実行しても通知は1件() throws Exception {
        Fixture fx = fixture();
        long expired = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        shiftInvitedAtDaysAgo(expired, 61);

        assertThat(trigger(EXPIRY)).isEqualTo(200);
        assertThat(trigger(EXPIRY)).isEqualTo(200);

        assertThat(statusOf(expired)).isNull();
        assertThat(jobIdsOf(expired, EXPIRED)).hasSize(1);
        assertThat(expiredAudits(fx.team().id())).hasSize(1);
    }

    @Test
    @DisplayName("AC-E05（cleanup）・E06 イベントを取りこぼしてアーカイブ済みの組織に残った PENDING は、手動起動 API で起動した "
            + "team-org-lifecycle-cleanup が 200 で片付けて取消を予約する")
    void 片付けバッチを手動起動APIで動かせる() throws Exception {
        Fixture fx = fixture();
        long pending = membership(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null);
        long active = membership(extraTeam().team().id(), fx.org().id(), "ACTIVE", "ORG_INVITE", null);
        // イベントを出さずに組織のアーカイブだけをコミットする（リスナーの前にプロセスが止まった状況）
        inTxVoid(() -> em.createNativeQuery("UPDATE organizations SET archived_at = UTC_TIMESTAMP(), "
                        + "closure_generation = closure_generation + 1 WHERE id = :id")
                .setParameter("id", fx.org().id()).executeUpdate());

        assertThat(trigger(LIFECYCLE_CLEANUP)).isEqualTo(200);

        assertThat(statusOf(pending)).isNull();
        assertThat(statusOf(active)).as("ACTIVE は残る").isEqualTo("ACTIVE");
        assertThat(jobIdsOf(pending, CANCELLED)).hasSize(1);
    }

    // =====================================================================
    // 補助
    // =====================================================================

    /** SYSTEM_ADMIN で {@code trigger?sync=true} を呼び、HTTP ステータスを返す。 */
    private int trigger(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/system-admin/batch/{name}/trigger", name)
                        .param("sync", "true")
                        .with(user("1").roles("SYSTEM_ADMIN")))
                .andReturn().getResponse().getStatus();
    }

    /** 実機E2E の準備と同じく、作成日時（invited_at）を過去へずらす（UTC の壁時計。§4.6）。 */
    private void shiftInvitedAtDaysAgo(long membershipId, int days) {
        jdbc.update("UPDATE team_org_memberships SET invited_at = UTC_TIMESTAMP() - INTERVAL ? DAY WHERE id = ?",
                days, membershipId);
    }

    private long pendingApplicationCount(long teamId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? "
                + "AND status = 'PENDING' AND direction = 'TEAM_APPLY'", Long.class, teamId);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> expiredAudits(long teamId) {
        return jdbc.queryForList("SELECT user_id, team_id, organization_id, metadata FROM audit_logs "
                + "WHERE event_type = 'TEAM_ORG_PENDING_EXPIRED' AND team_id = ?", teamId);
    }

    private void restriction(long teamId, long orgId, String direction, String kind, String untilSql) {
        jdbc.update("INSERT INTO team_org_affiliation_restrictions (id, organization_id, team_id, direction, kind, "
                + "reason, restricted_until, created_at, updated_at) VALUES (UNHEX(REPLACE(UUID(),'-','')), ?, ?, ?, ?, "
                + "'REJECTED', " + untilSql + ", UTC_TIMESTAMP(), UTC_TIMESTAMP())", orgId, teamId, direction, kind);
    }

    private List<String> restrictionKinds(long orgId) {
        return jdbc.queryForList("SELECT CONCAT(direction, ':', kind) FROM team_org_affiliation_restrictions "
                + "WHERE organization_id = ?", String.class, orgId);
    }

    private UUID groupIdOf(long membershipId) {
        List<byte[]> rows = jdbc.queryForList("SELECT group_id FROM team_org_memberships WHERE id = ?",
                byte[].class, membershipId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(rows.get(0));
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
