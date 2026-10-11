package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-C — 組織からの招待・承諾・辞退・取消と、両側の制限一覧・解除の統合テスト（試練）。
 *
 * <p>実 MySQL（Testcontainers）・実 Security フィルタ・MockMvc で実 API を叩く。認可・Service・Repository・
 * 組織ドメインの窓口はモックしない。{@code @Transactional} でテストごとにロールバックする。
 * 通知の配信と並行の検証は、コミットを要するため {@link TeamOrgInviteCommittedIT} が受け持つ。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える: XA＝組織X の ADMIN、XD＝組織X の DEPUTY_ADMIN、XM＝組織X の MEMBER、
 * YA＝組織Y の ADMIN、SYS＝SYSTEM_ADMIN、TA＝チームT の ADMIN、TD＝T の DEPUTY_ADMIN（付与なし）、
 * TM＝T の MEMBER（付与なし）、TG＝T の MEMBER で権限グループにより {@code MANAGE_ORG_AFFILIATION} を付与済み、
 * UA＝チームU の ADMIN。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-C 組織からの招待・承諾・辞退・取消と制限の解除")
class TeamOrgInviteIT extends TeamOrgInviteItSupport {

    private static final String NO_SUCH_TEAM_SLUG = "af-t-no-such-team";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamOrgAffiliationRestrictionService restrictionService;

    private TeamFx team;
    private TeamFx otherTeam;
    private OrgFx org;
    private OrgFx otherOrg;
    private long xa;
    private long xd;
    private long xm;
    private long ya;
    private long sys;
    private long ta;
    private long td;
    private long tm;
    private long tg;
    private long ua;

    /** 差し替えた Clock を元に戻すための退避。 */
    private Object originalRestrictionClock;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        otherTeam = newTeam();
        org = newOrg();
        otherOrg = newOrg();
        xa = newUser();
        xd = newUser();
        xm = newUser();
        ya = newUser();
        sys = newUser();
        ta = newUser();
        td = newUser();
        tm = newUser();
        tg = newUser();
        ua = newUser();
        makeOrgAdmin(xa, org.id());
        makeOrgDeputy(xd, org.id());
        makeOrgMember(xm, org.id());
        makeOrgAdmin(ya, otherOrg.id());
        makeSystemAdmin(sys);
        makeTeamAdmin(ta, team.id());
        makeTeamDeputy(td, team.id());
        makeTeamMember(tm, team.id());
        makeTeamMember(tg, team.id());
        grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
        makeTeamAdmin(ua, otherTeam.id());
        em.flush();
        em.clear();
    }

    @AfterEach
    void restoreClock() {
        if (originalRestrictionClock != null) {
            Object target = AopTestUtils.getTargetObject(restrictionService);
            ReflectionTestUtils.setField(target, "clock", originalRestrictionClock);
        }
    }

    // =====================================================================
    // AC-D01・G103a 招待
    // =====================================================================

    @Test
    @DisplayName("AC-D01 XA が公開チームを招待すると 201 で PENDING/ORG_INVITE ができ、TA の受信招待一覧に1件出る")
    void 招待すると201で受信一覧に出る() throws Exception {
        MvcResult result = invite(xa, org.slug(), team.slug(), null, "ぜひ加盟してください")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.direction").value("ORG_INVITE"))
                .andExpect(jsonPath("$.data.team.slug").value(team.slug()))
                .andExpect(jsonPath("$.data.organization.slug").value(org.slug()))
                .andExpect(jsonPath("$.data.message").value("ぜひ加盟してください"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn();
        long membershipId = json(result).get("data").get("id").asLong();
        assertThat(json(result).get("data").get("requestedBy").get("id").asLong()).isEqualTo(xa);

        Object[] row = membershipRow(membershipId);
        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isEqualTo("ORG_INVITE");

        MvcResult received = listReceived(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].organization.slug").value(org.slug()))
                .andReturn();
        assertThat(json(received).get("data").get(0).get("id").asLong()).isEqualTo(membershipId);
        assertThat(json(received).get("meta").get("total").asLong()).isEqualTo(1);

        listSent(xa, org.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].team.slug").value(team.slug()));
    }

    @Test
    @DisplayName("AC-G103a 招待すると TEAM_ORG_INVITE_SENT が audit_logs に1行残る（操作者 XA・組織・加盟 ID）")
    void 招待の監査ログが残る() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        List<?> rows = auditRows("TEAM_ORG_INVITE_SENT");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).as("操作者").isEqualTo(xa);
        assertThat(((Number) audit[2]).longValue()).as("組織コンテキスト").isEqualTo(org.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(membershipId);
        assertThat(metadata.get("team_id").asLong()).isEqualTo(team.id());
    }

    @Test
    @DisplayName("招待の前提: 加盟済みは 409 TEAM_065、相手から申請が来ていれば 409 TEAM_066、二重招待も 409 TEAM_066")
    void 招待の重複は409() throws Exception {
        TeamFx joined = newTeam();
        TeamFx applying = newTeam();
        insertMembershipRow(joined.id(), org.id(), "ACTIVE", "ORG_INVITE", null, LocalDateTime.now());
        insertMembershipRow(applying.id(), org.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        em.flush();

        invite(xa, org.slug(), joined.slug(), null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_065"));
        invite(xa, org.slug(), applying.slug(), null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_066"));

        invite(xa, org.slug(), team.slug(), null, null).andExpect(status().isCreated());
        invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_066"));
        assertThat(countMemberships(team.id(), org.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("招待の message は 500 文字で成功・501 文字で 400。空文字は null で保存する")
    void 招待のmessage() throws Exception {
        TeamFx another = newTeam();
        TeamFx blank = newTeam();
        invite(xa, org.slug(), team.slug(), null, "あ".repeat(501))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(countMemberships(team.id(), org.id())).isZero();
        invite(xa, org.slug(), another.slug(), null, "あ".repeat(500)).andExpect(status().isCreated());

        long blankId = inviteAndGetId(xa, org.slug(), blank.slug(), "   ");
        assertThat(membershipRow(blankId)[3]).isNull();
    }

    // =====================================================================
    // AC-D02・G103c・G104(ACTIVE 化) 承諾
    // =====================================================================

    @Test
    @DisplayName("AC-D02 TA が承諾すると 200 で ACTIVE になり、招待時に指定したグループに所属する（message は NULL に戻る）")
    void 承諾すると招待時のグループに所属する() throws Exception {
        OrgFx grouped = newOrg(true, true, "OPTIONAL", "PUBLIC");
        long groupedAdmin = newUser();
        makeOrgAdmin(groupedAdmin, grouped.id());
        UUID g1 = newGroup(grouped.id(), "G1", false);
        em.flush();

        MvcResult invited = invite(groupedAdmin, grouped.slug(), team.slug(), g1, "添え書き")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamGroup.id").value(g1.toString()))
                .andReturn();
        long membershipId = json(invited).get("data").get("id").asLong();

        accept(ta, team.slug(), membershipId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.direction").value("ORG_INVITE"))
                .andExpect(jsonPath("$.data.teamGroup.id").value(g1.toString()))
                .andExpect(jsonPath("$.data.teamGroup.name").value("G1"))
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.message").doesNotExist());

        Object[] row = membershipRow(membershipId);
        assertThat(row[0]).isEqualTo("ACTIVE");
        assertThat(uuidOf(row[2])).isEqualTo(g1);
        assertThat(row[3]).as("ACTIVE になると添え書きは NULL に戻る（AC-G104）").isNull();
        assertThat(((Number) row[4]).longValue()).as("responded_by").isEqualTo(ta);
        listReceived(ta, team.slug()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("AC-G103c 承諾すると TEAM_ORG_MEMBERSHIP_CREATED（via=ORG_INVITE）が audit_logs に1行残る")
    void 承諾の監査ログが残る() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        accept(ta, team.slug(), membershipId).andExpect(status().isOk());

        List<?> rows = auditRows("TEAM_ORG_MEMBERSHIP_CREATED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).isEqualTo(ta);
        assertThat(((Number) audit[2]).longValue()).isEqualTo(org.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("via").asText()).isEqualTo("ORG_INVITE");
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(membershipId);
    }

    @Test
    @DisplayName("AC-D08 移行前からある PENDING 招待（invited_by なし）を TA が承諾でき、ORG_INVITE として扱われる")
    void 移行前の招待を承諾できる() throws Exception {
        long legacyId = insertMembershipRow(team.id(), org.id(), "PENDING", "ORG_INVITE", null,
                LocalDateTime.of(2026, 1, 10, 9, 0));
        em.flush();

        listReceived(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].direction").value("ORG_INVITE"))
                .andExpect(jsonPath("$.data[0].requestedBy").doesNotExist());
        accept(ta, team.slug(), legacyId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.direction").value("ORG_INVITE"));
        assertThat(membershipRow(legacyId)[0]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC-D11 招待で指定したグループが承諾の前に削除されたら、承諾は 200 で未分類（group_id=NULL）のまま ACTIVE")
    void 削除済みグループの招待は未分類で承諾される() throws Exception {
        OrgFx grouped = newOrg(true, true, "OPTIONAL", "PUBLIC");
        long groupedAdmin = newUser();
        makeOrgAdmin(groupedAdmin, grouped.id());
        UUID g1 = newGroup(grouped.id(), "消えるグループ", false);
        em.flush();
        long membershipId = inviteAndGetId(groupedAdmin, grouped.slug(), team.slug(), null, g1);

        softDeleteGroup(g1);
        em.flush();

        accept(ta, team.slug(), membershipId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(membershipRow(membershipId)[2]).as("未分類で加盟が成立する").isNull();
    }

    @Test
    @DisplayName("承諾の時点でグループ機能が off なら group_id は NULL で ACTIVE になる")
    void グループ機能offなら承諾は未分類() throws Exception {
        OrgFx grouped = newOrg(true, true, "OPTIONAL", "PUBLIC");
        long groupedAdmin = newUser();
        makeOrgAdmin(groupedAdmin, grouped.id());
        UUID g1 = newGroup(grouped.id(), "G1", false);
        em.flush();
        long membershipId = inviteAndGetId(groupedAdmin, grouped.slug(), team.slug(), null, g1);
        em.createNativeQuery("UPDATE organizations SET team_groups_enabled = 0 WHERE id = :id")
                .setParameter("id", grouped.id()).executeUpdate();
        // 招待の時に読み込んだ組織 Entity が同じテストトランザクションの永続化コンテキストに残っていると、
        // ロック付きの読み取りでもキャッシュ済みの（機能 on の）値が返る。本番はリクエストごとに別トランザクションなので、
        // ここでも「別のリクエストで off にされた」状態を再現するためにコンテキストを捨てる
        em.flush();
        em.clear();

        accept(ta, team.slug(), membershipId).andExpect(status().isOk());
        assertThat(membershipRow(membershipId)[2]).isNull();
    }

    // =====================================================================
    // AC-D03・G103e 辞退 / AC-D04 チーム側の解除
    // =====================================================================

    @Test
    @DisplayName("AC-D03 TA が辞退すると行が消え、直後の再招待は 403 TEAM_068。30日を過ぎれば再招待できる")
    void 辞退後の再招待は30日止まる() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        decline(ta, team.slug(), membershipId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction.kind").value("COOLDOWN"))
                .andExpect(jsonPath("$.data.restriction.restrictedUntil").isNotEmpty());
        assertThat(countMemberships(team.id(), org.id())).as("辞退で行は物理削除される").isZero();

        invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));
        clock.advance(Duration.ofDays(29));
        invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));
        clock.advance(Duration.ofDays(2));
        invite(xa, org.slug(), team.slug(), null, null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-D03 block=true で辞退すると、30日を過ぎても再招待は 403 TEAM_068（冷却と同じ応答）")
    void ブロック辞退は期限なしで止まる() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        decline(ta, team.slug(), membershipId, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction.kind").value("BLOCK"))
                .andExpect(jsonPath("$.data.restriction.restrictedUntil").doesNotExist());

        MvcResult blocked = invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andReturn();
        clock.advance(Duration.ofDays(400));
        MvcResult stillBlocked = invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andReturn();
        assertThat(json(stillBlocked).get("error").get("code").asText()).isEqualTo("TEAM_068");
        assertThat(blocked.getResponse().getContentAsString())
                .as("冷却中とブロック中で相手に見える応答は同じ")
                .isEqualTo(stillBlocked.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("AC-G103e 辞退すると TEAM_ORG_INVITE_REJECTED（block 付き）が audit_logs に1行残る")
    void 辞退の監査ログが残る() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        decline(ta, team.slug(), membershipId, true).andExpect(status().isOk());

        List<?> rows = auditRows("TEAM_ORG_INVITE_REJECTED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).isEqualTo(ta);
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(membershipId);
        assertThat(metadata.get("block").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AC-D04 TA が招待の制限一覧からブロックを解除すると、XA は再招待できる")
    void チーム側で解除すると再招待できる() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);
        decline(ta, team.slug(), membershipId, true).andExpect(status().isOk());

        MvcResult list = listTeamRestrictions(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("BLOCK"))
                .andExpect(jsonPath("$.data[0].reason").value("DECLINED"))
                .andExpect(jsonPath("$.data[0].organization.slug").value(org.slug()))
                .andReturn();
        String restrictionId = json(list).get("data").get(0).get("id").asText();
        assertThat(restrictionId).isEqualTo(String.valueOf(restrictionIdOf(org.id(), team.id(), "ORG_INVITE")));

        liftTeamRestriction(ta, team.slug(), restrictionId).andExpect(status().isNoContent());
        assertThat(countRestrictions(org.id(), team.id())).isZero();

        invite(xa, org.slug(), team.slug(), null, null).andExpect(status().isCreated());
    }

    // =====================================================================
    // AC-D05・G103f・G138 取消
    // =====================================================================

    @Test
    @DisplayName("AC-D05 XA が招待を取り消すと 204 で TA の受信一覧から消え、直後の再招待は 403 TEAM_068、24時間後は 201")
    void 取消は24時間止まる() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);
        inviteAndGetId(xa, org.slug(), team.slug(), null);

        cancel(xa, org.slug(), team.slug()).andExpect(status().isNoContent());
        assertThat(countMemberships(team.id(), org.id())).isZero();
        listReceived(ta, team.slug()).andExpect(jsonPath("$.data.length()").value(0));

        invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));
        clock.advance(Duration.ofHours(23));
        invite(xa, org.slug(), team.slug(), null, null).andExpect(status().isForbidden());
        clock.advance(Duration.ofHours(2));
        invite(xa, org.slug(), team.slug(), null, null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-G103f 取り消すと TEAM_ORG_INVITE_CANCELLED が audit_logs に1行残る")
    void 取消の監査ログが残る() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        cancel(xa, org.slug(), team.slug()).andExpect(status().isNoContent());

        List<?> rows = auditRows("TEAM_ORG_INVITE_CANCELLED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).isEqualTo(xa);
        assertThat(objectMapper.readTree(String.valueOf(audit[3])).get("membership_id").asLong())
                .isEqualTo(membershipId);
    }

    @Test
    @DisplayName("AC-G138 取消の判定表: 招待が無い・存在しない slug は同じ 404 TEAM_070、ACTIVE・申請の行は 409 TEAM_071")
    void 取消の判定表() throws Exception {
        TeamFx active = newTeam();
        TeamFx applying = newTeam();
        insertMembershipRow(active.id(), org.id(), "ACTIVE", "ORG_INVITE", null, LocalDateTime.now());
        insertMembershipRow(applying.id(), org.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        em.flush();

        MvcResult noInvite = cancel(xa, org.slug(), team.slug())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        MvcResult noTeam = cancel(xa, org.slug(), NO_SUCH_TEAM_SLUG)
                .andExpect(status().isNotFound()).andReturn();
        assertThat(noTeam.getResponse().getContentAsString())
                .as("存在しないチームと招待の無いチームを区別しない")
                .isEqualTo(noInvite.getResponse().getContentAsString());

        cancel(xa, org.slug(), active.slug())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        cancel(xa, org.slug(), applying.slug())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        assertThat(countMemberships(active.id(), org.id())).as("409 のときは行を消さない").isEqualTo(1);
        assertThat(countMemberships(applying.id(), org.id())).isEqualTo(1);
        assertThat(countRestrictions(org.id(), active.id())).as("409 のときは制限を作らない").isZero();
    }

    // =====================================================================
    // AC-D06・P02 チーム側の認可 / AC-D07・K04 越境
    // =====================================================================

    @Test
    @DisplayName("AC-D06 TM・TD（付与なし）が承諾・辞退すると 403 で、行は PENDING のまま。受信一覧・制限一覧・解除も 403")
    void 付与のない人は承諾も辞退も403() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);
        restrictionService.record(otherOrg.id(), team.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED, TeamOrgAffiliationRestrictionKind.BLOCK, null, ta);
        em.flush();
        String restrictionId = String.valueOf(restrictionIdOf(otherOrg.id(), team.id(), "ORG_INVITE"));

        for (long actor : List.of(tm, td)) {
            accept(actor, team.slug(), membershipId)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            decline(actor, team.slug(), membershipId, true).andExpect(status().isForbidden());
            listReceived(actor, team.slug()).andExpect(status().isForbidden());
            listTeamRestrictions(actor, team.slug()).andExpect(status().isForbidden());
            liftTeamRestriction(actor, team.slug(), restrictionId).andExpect(status().isForbidden());
        }
        assertThat(membershipRow(membershipId)[0]).isEqualTo("PENDING");
        assertThat(countRestrictions(otherOrg.id(), team.id())).as("403 では解除されない").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-P04 MEMBER 向けの権限グループで付与された TG は承諾できる")
    void 付与されたメンバーは承諾できる() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        listReceived(tg, team.slug()).andExpect(status().isOk());
        accept(tg, team.slug(), membershipId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-D07・K04 チームU の ADMIN が T 宛て招待の ID を U の slug で承諾・辞退すると、存在しない ID と同じ 404 TEAM_070")
    void 他チームの招待IDは404() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);

        MvcResult crossAccept = accept(ua, otherTeam.slug(), membershipId)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        MvcResult missing = accept(ua, otherTeam.slug(), 987_654_321L)
                .andExpect(status().isNotFound()).andReturn();
        assertThat(crossAccept.getResponse().getContentAsString())
                .as("他チームの ID と存在しない ID を区別しない")
                .isEqualTo(missing.getResponse().getContentAsString());
        MvcResult crossDecline = decline(ua, otherTeam.slug(), membershipId, true)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        MvcResult missingDecline = decline(ua, otherTeam.slug(), 987_654_321L, true)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070")).andReturn();
        assertThat(crossDecline.getResponse().getContentAsString())
                .as("辞退の経路でも、他チームの ID と存在しない ID はステータス・エラーコード・本文まで同じ")
                .isEqualTo(missingDecline.getResponse().getContentAsString());

        assertThat(membershipRow(membershipId)[0]).as("越境では行が変わらない").isEqualTo("PENDING");
        assertThat(countRestrictions(org.id(), team.id())).isZero();
    }

    @Test
    @DisplayName("K04(チーム側) 申請の行・ACTIVE の行を招待として承諾・辞退すると 409 TEAM_071")
    void 招待でない行の承諾は409() throws Exception {
        OrgFx applied = newOrg();
        OrgFx joined = newOrg();
        long applicationId = insertMembershipRow(team.id(), applied.id(), "PENDING", "TEAM_APPLY", null,
                LocalDateTime.now());
        long activeId = insertMembershipRow(team.id(), joined.id(), "ACTIVE", "ORG_INVITE", null,
                LocalDateTime.now());
        em.flush();

        accept(ta, team.slug(), applicationId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        decline(ta, team.slug(), applicationId, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        accept(ta, team.slug(), activeId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        assertThat(membershipRow(applicationId)[0]).isEqualTo("PENDING");
        assertThat(countRestrictions(applied.id(), team.id())).isZero();
    }

    // =====================================================================
    // AC-D09 見えないチームへの招待は、存在しない slug と同じ 404
    // =====================================================================

    @Test
    @DisplayName("AC-D09 見えない非公開チームへの招待は、存在しない slug と同じステータス・同じ本文（加盟済み・制限中・申請中でも同じ）")
    void 見えないチームへの招待は存在しないslugと同じ404() throws Exception {
        TeamFx privatePlain = newTeam();
        TeamFx privateJoined = newTeam();
        TeamFx privateRestricted = newTeam();
        TeamFx privateApplying = newTeam();
        for (TeamFx t : List.of(privatePlain, privateJoined, privateRestricted, privateApplying)) {
            makeTeamPrivate(t.id());
        }
        insertMembershipRow(privateJoined.id(), org.id(), "ACTIVE", "TEAM_APPLY", null, LocalDateTime.now());
        insertMembershipRow(privateApplying.id(), org.id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        restrictionService.record(org.id(), privateRestricted.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED, TeamOrgAffiliationRestrictionKind.BLOCK, null, null);
        em.flush();
        em.clear();

        MvcResult missing = invite(xa, org.slug(), NO_SUCH_TEAM_SLUG, null, null)
                .andExpect(status().isNotFound()).andReturn();
        String missingBody = missing.getResponse().getContentAsString();
        for (TeamFx t : List.of(privatePlain, privateJoined, privateRestricted, privateApplying)) {
            MvcResult invisible = invite(xa, org.slug(), t.slug(), null, null)
                    .andExpect(status().isNotFound()).andReturn();
            assertThat(invisible.getResponse().getContentAsString())
                    .as("非公開チーム %s の応答は存在しない slug と本文まで一致する（TEAM_065・066・068 を返さない）", t.slug())
                    .isEqualTo(missingBody);
        }
        assertThat(countMemberships(privatePlain.id(), org.id())).as("見えないチームには行を作らない").isZero();
    }

    // =====================================================================
    // AC-D10 グループの検証
    // =====================================================================

    @Test
    @DisplayName("AC-D10 グループ機能 off で groupId を付けた招待は 400 TEAM_072。on でも他組織・削除済みのグループは 400 TEAM_072（行は作らない）")
    void 招待のグループ検証() throws Exception {
        invite(xa, org.slug(), team.slug(), UUID.randomUUID(), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("TEAM_072"));
        assertThat(countMemberships(team.id(), org.id())).isZero();

        OrgFx grouped = newOrg(true, true, "OFF", "PUBLIC");
        long groupedAdmin = newUser();
        makeOrgAdmin(groupedAdmin, grouped.id());
        UUID othersGroup = newGroup(otherOrg.id(), "他組織のグループ", false);
        UUID deletedGroup = newGroup(grouped.id(), "削除済みのグループ", true);
        UUID aliveGroup = newGroup(grouped.id(), "生存グループ", false);
        em.flush();

        invite(groupedAdmin, grouped.slug(), team.slug(), othersGroup, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("TEAM_072"));
        invite(groupedAdmin, grouped.slug(), team.slug(), deletedGroup, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("TEAM_072"));
        assertThat(countMemberships(team.id(), grouped.id())).isZero();

        invite(groupedAdmin, grouped.slug(), team.slug(), aliveGroup, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamGroup.id").value(aliveGroup.toString()));
    }

    // =====================================================================
    // AC-P08 組織側の操作は組織 ADMIN だけ
    // =====================================================================

    @Test
    @DisplayName("AC-P08 招待・取消・送信済み一覧・制限一覧・解除は、XD・XM・YA・SYS・TA（チーム側で権限あり）では 403 で、状態は変わらない")
    void 組織側の操作は組織ADMIN以外は403() throws Exception {
        long membershipId = inviteAndGetId(xa, org.slug(), team.slug(), null);
        TeamFx blockedTeam = newTeam();
        restrictionService.record(org.id(), blockedTeam.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.BLOCK, null, xa);
        em.flush();
        String restrictionId = String.valueOf(restrictionIdOf(org.id(), blockedTeam.id(), "TEAM_APPLY"));
        TeamFx target = newTeam();

        for (long actor : List.of(xd, xm, ya, sys, ta)) {
            invite(actor, org.slug(), target.slug(), null, null)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            listSent(actor, org.slug()).andExpect(status().isForbidden());
            cancel(actor, org.slug(), team.slug()).andExpect(status().isForbidden());
            listOrgRestrictions(actor, org.slug()).andExpect(status().isForbidden());
            liftOrgRestriction(actor, org.slug(), restrictionId).andExpect(status().isForbidden());
        }
        assertThat(countMemberships(target.id(), org.id())).as("403 では招待されない").isZero();
        assertThat(membershipRow(membershipId)[0]).as("403 では取り消されない").isEqualTo("PENDING");
        assertThat(countRestrictions(org.id(), blockedTeam.id())).as("403 では解除されない").isEqualTo(1);
    }

    // =====================================================================
    // AC-C09(解除)・C12・G110・G137 制限の一覧と解除
    // =====================================================================

    @Test
    @DisplayName("AC-C09 block=true の拒否で止まった申請は 403 TEAM_068 のまま。XA が制限一覧から解除すると直後の申請が 201")
    void 組織側で解除すると申請できる() throws Exception {
        restrictionService.record(org.id(), team.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.BLOCK, null, xa);
        em.flush();
        apply(ta, team.slug(), org.slug())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));

        MvcResult list = listOrgRestrictions(xa, org.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("BLOCK"))
                .andExpect(jsonPath("$.data[0].reason").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].team.slug").value(team.slug()))
                .andReturn();
        String restrictionId = json(list).get("data").get(0).get("id").asText();

        liftOrgRestriction(xa, org.slug(), restrictionId).andExpect(status().isNoContent());
        assertThat(countRestrictions(org.id(), team.id())).isZero();
        apply(ta, team.slug(), org.slug()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-C12 XA が別組織Y の制限 ID を解除しようとすると、存在しない ID と同じ 404 で、Y の制限は残る")
    void 他組織の制限IDは404() throws Exception {
        restrictionService.record(otherOrg.id(), team.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.BLOCK, null, ya);
        em.flush();
        String othersRestriction = String.valueOf(restrictionIdOf(otherOrg.id(), team.id(), "TEAM_APPLY"));

        MvcResult cross = liftOrgRestriction(xa, org.slug(), othersRestriction)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = liftOrgRestriction(xa, org.slug(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound()).andReturn();
        assertThat(cross.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(countRestrictions(otherOrg.id(), team.id())).isEqualTo(1);
        listOrgRestrictions(xa, org.slug()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("制限の迂回の封止（組織側）: 組織は、チーム自身の取下げで止まった申請・チームが止めた招待を解除できない（404）")
    void 組織は自分が作っていない制限を解除できない() throws Exception {
        TeamFx withdrawnTeam = newTeam();
        restrictionService.record(org.id(), withdrawnTeam.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.WITHDRAWN, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofHours(24), ta);
        restrictionService.record(org.id(), team.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED, TeamOrgAffiliationRestrictionKind.BLOCK, null, ta);
        em.flush();

        liftOrgRestriction(xa, org.slug(), String.valueOf(restrictionIdOf(org.id(), withdrawnTeam.id(), "TEAM_APPLY")))
                .andExpect(status().isNotFound());
        liftOrgRestriction(xa, org.slug(), String.valueOf(restrictionIdOf(org.id(), team.id(), "ORG_INVITE")))
                .andExpect(status().isNotFound());
        assertThat(countRestrictions(org.id(), withdrawnTeam.id())).isEqualTo(1);
        assertThat(countRestrictions(org.id(), team.id())).as("チームが止めた招待は組織からは解除できない").isEqualTo(1);
        invite(xa, org.slug(), team.slug(), null, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));
    }

    @Test
    @DisplayName("AC-G137 TA が他チームの制限 ID を解除しようとすると、存在しない ID と同じ 404 で、U の制限は残る")
    void 他チームの制限IDは404() throws Exception {
        restrictionService.record(org.id(), otherTeam.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED, TeamOrgAffiliationRestrictionKind.BLOCK, null, ua);
        em.flush();
        String othersRestriction = String.valueOf(restrictionIdOf(org.id(), otherTeam.id(), "ORG_INVITE"));

        MvcResult cross = liftTeamRestriction(ta, team.slug(), othersRestriction)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = liftTeamRestriction(ta, team.slug(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound()).andReturn();
        assertThat(cross.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(countRestrictions(org.id(), otherTeam.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("制限の迂回の封止（チーム側）: チームは、組織が拒否で止めた申請・組織自身の取消で止まった招待を解除できない（404）")
    void チームは自分が作っていない制限を解除できない() throws Exception {
        OrgFx cancelledOrg = newOrg();
        restrictionService.record(org.id(), team.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.BLOCK, null, xa);
        restrictionService.record(cancelledOrg.id(), team.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.CANCELLED, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofHours(24), xa);
        em.flush();

        liftTeamRestriction(ta, team.slug(), String.valueOf(restrictionIdOf(org.id(), team.id(), "TEAM_APPLY")))
                .andExpect(status().isNotFound());
        liftTeamRestriction(ta, team.slug(),
                String.valueOf(restrictionIdOf(cancelledOrg.id(), team.id(), "ORG_INVITE")))
                .andExpect(status().isNotFound());
        assertThat(countRestrictions(org.id(), team.id())).as("組織の拒否による制限はチームから解除できない").isEqualTo(1);
        apply(ta, team.slug(), org.slug())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TEAM_068"));
    }

    @Test
    @DisplayName("AC-G110 解除一覧には REJECTED（組織側）と DECLINED（チーム側）だけが出て、WITHDRAWN と CANCELLED は出ない")
    void 解除一覧は止めた側が作った制限だけ() throws Exception {
        TeamFx rejectedTeam = newTeam();
        TeamFx withdrawnTeam = newTeam();
        OrgFx declinedOrg = newOrg();
        OrgFx cancelledOrg = newOrg();
        restrictionService.record(org.id(), rejectedTeam.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.REJECTED, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofDays(30), xa);
        restrictionService.record(org.id(), withdrawnTeam.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.WITHDRAWN, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofHours(24), ta);
        restrictionService.record(declinedOrg.id(), team.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.DECLINED, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofDays(30), ta);
        restrictionService.record(cancelledOrg.id(), team.id(), TeamOrgAffiliationDirection.ORG_INVITE,
                TeamOrgAffiliationRestrictionReason.CANCELLED, TeamOrgAffiliationRestrictionKind.COOLDOWN,
                Duration.ofHours(24), xa);
        em.flush();

        MvcResult orgList = listOrgRestrictions(xa, org.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].kind").value("COOLDOWN"))
                .andExpect(jsonPath("$.data[0].restrictedUntil").isNotEmpty())
                .andReturn();
        assertThat(json(orgList).get("data").get(0).get("team").get("slug").asText()).isEqualTo(rejectedTeam.slug());

        MvcResult teamList = listTeamRestrictions(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("DECLINED"))
                .andReturn();
        assertThat(json(teamList).get("data").get(0).get("organization").get("slug").asText())
                .isEqualTo(declinedOrg.slug());
    }

    // =====================================================================
    // AC-G141 一覧の並びと範囲
    // =====================================================================

    @Test
    @DisplayName("AC-G141 送信済み招待一覧・受信招待一覧は requestedAt の降順で、他組織・他チーム・申請・ACTIVE を含まない")
    void 招待一覧は降順で自スコープの招待だけ() throws Exception {
        TeamFx oldest = newTeam();
        TeamFx newest = newTeam();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 9, 0);
        insertMembershipRow(oldest.id(), org.id(), "PENDING", "ORG_INVITE", null, base);
        insertMembershipRow(newest.id(), org.id(), "PENDING", "ORG_INVITE", null, base.plusDays(2));
        insertMembershipRow(team.id(), org.id(), "PENDING", "ORG_INVITE", null, base.plusDays(1));
        insertMembershipRow(newTeam().id(), otherOrg.id(), "PENDING", "ORG_INVITE", null, base);
        insertMembershipRow(newTeam().id(), org.id(), "PENDING", "TEAM_APPLY", null, base);
        insertMembershipRow(newTeam().id(), org.id(), "ACTIVE", "ORG_INVITE", null, base);
        insertMembershipRow(team.id(), otherOrg.id(), "PENDING", "TEAM_APPLY", null, base);
        em.flush();

        MvcResult sent = listSent(xa, org.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andReturn();
        List<String> slugs = new ArrayList<>();
        json(sent).get("data").forEach(n -> slugs.add(n.get("team").get("slug").asText()));
        assertThat(slugs).containsExactly(newest.slug(), team.slug(), oldest.slug());

        listReceived(ta, team.slug()).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].organization.slug").value(org.slug()));
        listSent(xa, org.slug(), "?size=1000").andExpect(jsonPath("$.meta.size").value(100));
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private ResultActions invite(long actor, String orgSlug, String teamSlug, UUID groupId, String message)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamSlug", teamSlug);
        body.put("groupId", groupId == null ? null : groupId.toString());
        body.put("message", message);
        return mockMvc.perform(post("/api/v1/organizations/{slug}/team-invites", orgSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long inviteAndGetId(long actor, String orgSlug, String teamSlug, String message) throws Exception {
        return inviteAndGetId(actor, orgSlug, teamSlug, message, null);
    }

    private long inviteAndGetId(long actor, String orgSlug, String teamSlug, String message, UUID groupId)
            throws Exception {
        MvcResult result = invite(actor, orgSlug, teamSlug, groupId, message)
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("data").get("id").asLong();
    }

    private ResultActions listSent(long actor, String orgSlug) throws Exception {
        return listSent(actor, orgSlug, "");
    }

    private ResultActions listSent(long actor, String orgSlug, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/organizations/{slug}/team-invites" + query, orgSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions cancel(long actor, String orgSlug, String teamSlug) throws Exception {
        return mockMvc.perform(delete("/api/v1/organizations/{slug}/team-invites/{teamSlug}", orgSlug, teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions listReceived(long actor, String teamSlug) throws Exception {
        return mockMvc.perform(get("/api/v1/teams/{teamSlug}/org-invites", teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions accept(long actor, String teamSlug, long membershipId) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", teamSlug, membershipId)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions decline(long actor, String teamSlug, long membershipId, Boolean block) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("block", block);
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/reject", teamSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listOrgRestrictions(long actor, String orgSlug) throws Exception {
        return mockMvc.perform(get("/api/v1/organizations/{slug}/team-affiliation-restrictions", orgSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions liftOrgRestriction(long actor, String orgSlug, String restrictionId) throws Exception {
        return mockMvc.perform(delete("/api/v1/organizations/{slug}/team-affiliation-restrictions/{id}",
                        orgSlug, restrictionId)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions listTeamRestrictions(long actor, String teamSlug) throws Exception {
        return mockMvc.perform(get("/api/v1/teams/{teamSlug}/org-affiliation-restrictions", teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions liftTeamRestriction(long actor, String teamSlug, String restrictionId) throws Exception {
        return mockMvc.perform(delete("/api/v1/teams/{teamSlug}/org-affiliation-restrictions/{id}",
                        teamSlug, restrictionId)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions apply(long actor, String teamSlug, String orgSlug) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("organizationSlug", orgSlug))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<?> auditRows(String eventType) {
        return em.createNativeQuery(
                        "SELECT user_id, team_id, organization_id, metadata FROM audit_logs "
                                + "WHERE event_type = :type AND team_id = :teamId")
                .setParameter("type", eventType)
                .setParameter("teamId", team.id())
                .getResultList();
    }

    /** 制限の判定・記録に使う時計を、進められる固定時計に差し替える。 */
    private void swapRestrictionClock(Clock clock) {
        Object target = AopTestUtils.getTargetObject(restrictionService);
        originalRestrictionClock = ReflectionTestUtils.getField(target, "clock");
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    /** 進められる固定時計。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
