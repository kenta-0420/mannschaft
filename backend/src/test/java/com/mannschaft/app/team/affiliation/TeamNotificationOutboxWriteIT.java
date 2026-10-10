package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.team.service.TeamAffiliationNotice;
import com.mannschaft.app.team.service.TeamAffiliationNotifier;
import com.mannschaft.app.team.service.TeamOrgAffiliationCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 通知 outbox P1 — 書き込み側（team の業務 tx と outbox の同時確定）の試練 IT。
 *
 * <p>AC（陣立て書 2026-10-10-notification-outbox-gungi.md §5 P1 と改訂第2版）:</p>
 * <ul>
 *   <li>OB01: 申請のコミットで outbox に PENDING 1行、relay 前は fan-out ジョブ 0行</li>
 *   <li>OB02: 申請の tx を強制ロールバックすると outbox 0行・ジョブ 0行</li>
 *   <li>OB02a: outbox の書き込みが失敗すると申請も作られずエラー</li>
 *   <li>OB05: 同じ tx で同じキーを2回書いても outbox は1行で、例外なく業務がコミットされる</li>
 *   <li>OB18: 拒否された操作（TEAM_064 / TEAM_066 / TEAM_069）では outbox が増えない</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("通知 outbox P1 書き込み側（業務 tx との同時確定）")
class TeamNotificationOutboxWriteIT extends TeamNotificationOutboxItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamOrgAffiliationCommandService commandService;

    /** outbox の書き込み失敗を起こすために差し替える（既定は実物を呼ぶ）。 */
    @MockitoSpyBean
    private TeamAffiliationNotifier notifierSpy;

    // =====================================================================
    // OB01
    // =====================================================================

    @Test
    @DisplayName("OB01 申請がコミットされると team_notification_outbox に PENDING が1行でき、relay の前は fan-out ジョブが無い")
    void ob01_申請のコミットでoutboxにPENDINGが1行できジョブはまだ無い() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });

        Applied applied = apply(admin, team.slug(), org.slug());
        assertThat(applied.status()).isEqualTo(201);

        Map<String, Object> row = outboxRowOf(applied.id());
        assertThat(row).as("申請と同じ tx で outbox に1行書かれている").isNotNull();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("message_kind")).isEqualTo("FANOUT");
        assertThat(((Number) row.get("payload_version")).intValue()).isEqualTo(1);
        assertThat(row.get("notification_type")).isEqualTo("TEAM_ORG_APPLICATION_RECEIVED");
        assertThat(((Number) row.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(outboxCount(org.id())).isEqualTo(1);
        assertThat(jobCountOf(applied.id())).as("relay の前は fan-out ジョブが無い（通知ドメインへは書かない）").isZero();
    }

    // =====================================================================
    // OB02
    // =====================================================================

    @Test
    @DisplayName("OB02 申請の tx を強制ロールバックすると、加盟行・outbox・fan-out ジョブがどれも残らない")
    void ob02_申請のtxをロールバックするとoutboxもジョブも残らない() {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long admin = inTx(this::newUser);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            commandService.apply(team.id(), org.id(), admin, null, null);
            status.setRollbackOnly();
        });

        assertThat(countMembershipsCommitted(team.id(), org.id())).isZero();
        assertThat(outboxCount(org.id())).as("outbox は業務と運命を共にする").isZero();
        assertThat(jobCountByOrganization(org.id())).isZero();
    }

    // =====================================================================
    // OB02a
    // =====================================================================

    @Test
    @DisplayName("OB02a outbox の書き込みが失敗すると、申請も作られずエラーになる（通知を出さずに状態だけ変わらない）")
    void ob02a_outboxの書き込み失敗で申請も作られない() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });
        doThrow(new DataIntegrityViolationException("outbox の INSERT 失敗（テスト）")).when(notifierSpy).enqueue(any());

        int status;
        try {
            status = apply(admin, team.slug(), org.slug()).status();
        } catch (Exception propagated) {
            // 例外が MockMvc まで伝わる形でもよい（応答の形ではなく、残ったものを検証する）
            status = 500;
        }

        assertThat(status).as("申請は成功しない").isNotEqualTo(201);
        assertThat(countMembershipsCommitted(team.id(), org.id())).as("申請の行は作られない").isZero();
        assertThat(outboxCount(org.id())).isZero();
        assertThat(jobCountByOrganization(org.id())).isZero();
    }

    // =====================================================================
    // OB05
    // =====================================================================

    @Test
    @DisplayName("OB05 同じ tx で同じ冪等キーを2回書いても outbox は1行で、例外にならず業務の書き込みもコミットされる")
    void ob05_同じtxで同じキーを2回書いてもoutboxは1行で業務はコミットされる() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = nextFakeMembershipId();
        TeamAffiliationNotice notice = TeamAffiliationNotice.applicationReceived(
                org.id(), org.slug(), "outbox試験チーム", org.name(), membershipId, null);

        TeamFx team = inTx(() -> {
            TeamFx created = newTeam(); // 業務の書き込みの代わり（同じ tx でコミットされることを確かめる）
            notifier.enqueue(notice);
            notifier.enqueue(notice);
            return created;
        });

        assertThat(outboxCount(org.id())).as("UNIQUE(idempotency_key) で1行に収束").isEqualTo(1);
        assertThat(outboxRow(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId)))
                .isNotNull();
        Long teams = jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id = ?", Long.class, team.id());
        assertThat(teams).as("重複で tx が rollback-only にならず、業務の書き込みはコミットされる").isEqualTo(1L);
    }

    // =====================================================================
    // OB18
    // =====================================================================

    @Test
    @DisplayName("OB18 拒否された申請（受付 off の TEAM_064・重複の TEAM_066・上限の TEAM_069）では outbox が増えない")
    void ob18_拒否された操作ではoutboxが増えない() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx closed = inTx(() -> newOrg(false, false, "OFF", "PUBLIC"));
        OrgFx open = inTx(this::newOrg);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });

        // TEAM_064（受付 off）
        Applied rejected = apply(admin, team.slug(), closed.slug());
        assertThat(rejected.errorCode()).isEqualTo("TEAM_064");
        assertThat(outboxCount(closed.id())).isZero();

        // TEAM_066（同じ組織への二重申請。1件目は成功して outbox 1行）
        assertThat(apply(admin, team.slug(), open.slug()).status()).isEqualTo(201);
        long before = outboxCount(open.id());
        Applied duplicate = apply(admin, team.slug(), open.slug());
        assertThat(duplicate.errorCode()).isEqualTo("TEAM_066");
        assertThat(outboxCount(open.id())).as("重複の申請で outbox が増えない").isEqualTo(before);

        // TEAM_069（PENDING が上限の10件）
        TeamFx full = inTx(this::newTeam);
        long fullAdmin = inTx(() -> {
            long u = newUser();
            makeTeamAdmin(u, full.id());
            for (int i = 0; i < 10; i++) {
                insertMembershipRow(full.id(), newOrg().id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
            }
            return u;
        });
        OrgFx eleventh = inTx(this::newOrg);
        Applied overLimit = apply(fullAdmin, full.slug(), eleventh.slug());
        assertThat(overLimit.errorCode()).isEqualTo("TEAM_069");
        assertThat(outboxCount(eleventh.id())).isZero();
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private record Applied(int status, String errorCode, long id) {
    }

    private Applied apply(long actor, String teamSlug, String organizationSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                        .with(user(String.valueOf(actor)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("organizationSlug", organizationSlug))))
                .andReturn();
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        if (content.isBlank()) {
            return new Applied(status, null, 0L);
        }
        JsonNode body = objectMapper.readTree(content);
        return new Applied(status, body.path("error").path("code").asText(null),
                body.path("data").path("id").asLong(0L));
    }

    private long countMembershipsCommitted(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }
}
