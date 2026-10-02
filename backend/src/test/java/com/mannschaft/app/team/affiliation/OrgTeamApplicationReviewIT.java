package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.ScopeAncestorResolver;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-B2 — 組織側の受信申請一覧・承認（§6.2）・拒否（§6.3）・判定表（§6.4）の統合テスト（試練）。
 *
 * <p>実 MySQL（Testcontainers）・実 Security フィルタ・MockMvc で実 API を叩く。認可・Service・Repository・
 * 組織ドメインの窓口はモックしない。{@code @Transactional} でテストごとにロールバックする。
 * 通知（Worker による配信）と並行の競合は、コミットを要するため {@link OrgTeamApplicationReviewCommittedIT} で確かめる。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える: XA＝組織X の ADMIN、XD＝組織X の DEPUTY_ADMIN、XM＝組織X の MEMBER、
 * YA＝組織Y の ADMIN、TA＝チームT の ADMIN、TG＝権限グループで加盟操作権限を付与された T の MEMBER。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-B2 組織側の申請一覧・承認・拒否")
class OrgTeamApplicationReviewIT extends TeamAffiliationItSupport {

    private static final String APPLICATIONS = "/api/v1/organizations/{slug}/team-applications";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamOrgAffiliationRestrictionService restrictionService;

    @Autowired
    private ScopeAncestorResolver scopeAncestorResolver;

    @Autowired
    private ScheduleRepository scheduleRepository;

    private TeamFx team;
    private OrgFx orgX;
    private OrgFx orgY;
    private UUID groupA;
    private UUID groupB;
    private long ta;
    private long tg;
    private long xa;
    private long xd;
    private long xm;
    private long ya;

    /** 差し替えた Clock を元に戻すための退避。 */
    private Object originalRestrictionClock;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        orgX = newOrg(true, true, "OPTIONAL", "PUBLIC");
        orgY = newOrg(true, false, "OFF", "PUBLIC");
        groupA = newGroup(orgX.id(), "グループA", false);
        groupB = newGroup(orgX.id(), "グループB", false);
        ta = newUser();
        tg = newUser();
        xa = newUser();
        xd = newUser();
        xm = newUser();
        ya = newUser();
        makeTeamAdmin(ta, team.id());
        makeTeamMember(tg, team.id());
        grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
        makeOrgAdmin(xa, orgX.id());
        makeOrgDeputy(xd, orgX.id());
        makeOrgMember(xm, orgX.id());
        makeOrgAdmin(ya, orgY.id());
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
    // AC-C01・G104 承認（希望グループのまま）
    // =====================================================================

    @Test
    @DisplayName("AC-C01 overrideGroup=false で承認すると 200 で ACTIVE になり、希望グループに所属し、添え書きは NULL に戻る")
    void 希望グループのまま承認する() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), groupA);
        em.createNativeQuery("UPDATE team_org_memberships SET message = 'よろしくお願いします' WHERE id = :id")
                .setParameter("id", id).executeUpdate();

        approve(xa, orgX.slug(), id, false, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.direction").value("TEAM_APPLY"))
                .andExpect(jsonPath("$.data.team.slug").value(team.slug()))
                .andExpect(jsonPath("$.data.organization.slug").value(orgX.slug()))
                .andExpect(jsonPath("$.data.teamGroup.id").value(groupA.toString()))
                .andExpect(jsonPath("$.data.teamGroup.name").value("グループA"))
                .andExpect(jsonPath("$.data.message").doesNotExist())
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").doesNotExist());

        Object[] row = membershipRow(id);
        assertThat(row[0]).isEqualTo("ACTIVE");
        assertThat(groupIdOf(id)).isEqualTo(groupA);
        assertThat(row[1]).as("AC-G104 ACTIVE になったら添え書きは NULL に戻す").isNull();
        assertThat(((Number) row[2]).longValue()).as("responded_by").isEqualTo(xa);
        assertThat(row[3]).as("responded_at").isNotNull();
    }

    @Test
    @DisplayName("overrideGroup=false で、希望グループが承認の前に削除されていたら未分類（NULL）で承認する")
    void 希望グループが削除済みなら未分類で承認する() throws Exception {
        UUID deleted = newGroup(orgX.id(), "削除済み", true);
        long id = pendingApplication(team.id(), orgX.id(), deleted);

        approve(xa, orgX.slug(), id, false, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(groupIdOf(id)).isNull();
    }

    // =====================================================================
    // AC-C02・G103d 別グループで承認と監査ログ
    // =====================================================================

    @Test
    @DisplayName("AC-C02・G103d 別グループを指定して承認するとそのグループに所属し、監査ログに希望と確定の両方が残る")
    void 別グループで承認すると監査に希望と確定が残る() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), groupA);

        approve(xa, orgX.slug(), id, true, groupB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamGroup.id").value(groupB.toString()));
        assertThat(groupIdOf(id)).isEqualTo(groupB);

        List<?> rows = auditRows("TEAM_ORG_MEMBERSHIP_CREATED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).as("操作者").isEqualTo(xa);
        assertThat(((Number) audit[2]).longValue()).as("組織コンテキスト").isEqualTo(orgX.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(id);
        assertThat(metadata.get("via").asText()).isEqualTo("TEAM_APPLY");
        assertThat(metadata.get("requested_group_id").asText()).isEqualTo(groupA.toString());
        assertThat(metadata.get("group_id").asText()).isEqualTo(groupB.toString());
    }

    @Test
    @DisplayName("overrideGroup=true で他組織のグループ・削除済みグループを指定すると 400 TEAM_072 で、PENDING のまま")
    void 上書きグループの検証() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), groupA);
        OrgFx other = newOrg(true, true, "OPTIONAL", "PUBLIC");
        UUID otherGroup = newGroup(other.id(), "他組織のグループ", false);
        UUID deleted = newGroup(orgX.id(), "削除済み", true);

        for (UUID bad : List.of(otherGroup, deleted, UUID.randomUUID())) {
            approve(xa, orgX.slug(), id, true, bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("TEAM_072"));
        }
        assertThat(membershipRow(id)[0]).isEqualTo("PENDING");
        assertThat(groupIdOf(id)).isEqualTo(groupA);
    }

    @Test
    @DisplayName("overrideGroup を送らないと 400 で、PENDING のまま")
    void overrideGroupは必須() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), groupA);

        approve(xa, orgX.slug(), id, null, null).andExpect(status().isBadRequest());
        assertThat(membershipRow(id)[0]).isEqualTo("PENDING");
    }

    // =====================================================================
    // AC-C03 未分類で承認（REQUIRED でも成功）
    // =====================================================================

    @Test
    @DisplayName("AC-C03 overrideGroup=true・groupId=null で承認すると未分類になる（REQUIRED の組織でも成功する）")
    void 未分類で承認する() throws Exception {
        OrgFx required = newOrg(true, true, "REQUIRED", "PUBLIC");
        UUID requiredGroup = newGroup(required.id(), "必須グループ", false);
        long admin = newUser();
        makeOrgAdmin(admin, required.id());
        long id = pendingApplication(team.id(), required.id(), requiredGroup);

        approve(admin, required.slug(), id, true, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        assertThat(groupIdOf(id)).isNull();
    }

    // =====================================================================
    // AC-G121 グループ機能 off なら常に NULL
    // =====================================================================

    @Test
    @DisplayName("AC-G121 グループ機能 off の組織で承認すると、希望グループがあっても上書き指定があっても group_id は NULL")
    void グループ機能offなら常にNULL() throws Exception {
        OrgFx off = newOrg(true, false, "OFF", "PUBLIC");
        UUID storedGroup = newGroup(off.id(), "off の間も残るグループ", false);
        long admin = newUser();
        makeOrgAdmin(admin, off.id());
        TeamFx team2 = newTeam();
        long kept = pendingApplication(team.id(), off.id(), storedGroup);
        long overridden = pendingApplication(team2.id(), off.id(), null);

        approve(admin, off.slug(), kept, false, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
        approve(admin, off.slug(), overridden, true, storedGroup)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());

        assertThat(groupIdOf(kept)).isNull();
        assertThat(groupIdOf(overridden)).isNull();
    }

    // =====================================================================
    // AC-A07 受付 off 後の PENDING
    // =====================================================================

    @Test
    @DisplayName("AC-A07 PENDING 申請が2件ある状態で受付を off にしても2件は残り、承認・拒否できる")
    void 受付offでも残ったPENDINGは承認拒否できる() throws Exception {
        TeamFx team2 = newTeam();
        long first = pendingApplication(team.id(), orgX.id(), null);
        long second = pendingApplication(team2.id(), orgX.id(), null);
        em.createNativeQuery("UPDATE organizations SET team_application_enabled = 0 WHERE id = :id")
                .setParameter("id", orgX.id()).executeUpdate();

        list(xa, orgX.slug(), "").andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(2));
        approve(xa, orgX.slug(), first, false, null).andExpect(status().isOk());
        reject(xa, orgX.slug(), second, null, null).andExpect(status().isOk());

        assertThat(membershipRow(first)[0]).isEqualTo("ACTIVE");
        assertThat(countMemberships(team2.id(), orgX.id())).isZero();
    }

    // =====================================================================
    // AC-C05・P08 組織 ADMIN 以外は 403
    // =====================================================================

    @Test
    @DisplayName("AC-C05・P08 XD・XM・YA（X の slug）・TA・TG が一覧・承認・拒否すると 403 で、状態は変わらない")
    void 組織ADMIN以外は403() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), groupA);

        for (long actor : List.of(xd, xm, ya, ta, tg)) {
            list(actor, orgX.slug(), "").andExpect(status().isForbidden());
            approve(actor, orgX.slug(), id, false, null).andExpect(status().isForbidden());
            reject(actor, orgX.slug(), id, "理由", true).andExpect(status().isForbidden());
        }
        assertThat(membershipRow(id)[0]).isEqualTo("PENDING");
        assertThat(restrictionCount(team.id(), orgX.id())).isZero();
    }

    @Test
    @DisplayName("未認証で一覧・承認・拒否を呼ぶと 401")
    void 未認証は401() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), null);

        mockMvc.perform(get(APPLICATIONS, orgX.slug())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(APPLICATIONS + "/{id}/approve", orgX.slug(), id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"overrideGroup\":false}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(APPLICATIONS + "/{id}/reject", orgX.slug(), id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // AC-C06・K04 存在オラクルを作らない
    // =====================================================================

    @Test
    @DisplayName("AC-C06 YA が組織Y の slug で組織X宛ての申請 ID を承認・拒否すると 404 TEAM_070（存在しない ID と同じ応答）")
    void 他組織のslugで申請IDを指定すると404() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), null);

        String forOther = approve(ya, orgY.slug(), id, false, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_070"))
                .andReturn().getResponse().getContentAsString();
        String forMissing = approve(ya, orgY.slug(), 987_654_321L, false, null)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(errorOf(forOther)).isEqualTo(errorOf(forMissing));

        reject(ya, orgY.slug(), id, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_070"));
        assertThat(membershipRow(id)[0]).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("AC-K04 他組織の行（PENDING 申請・ACTIVE・招待）と存在しない ID は、承認でも拒否でも同じ 404 TEAM_070・同じ本文")
    void membershipIdを総当たりしても区別できない() throws Exception {
        TeamFx teamU = newTeam();
        TeamFx teamV = newTeam();
        long otherApplication = pendingApplication(teamU.id(), orgY.id(), null);
        long otherActive = insertMembershipRow(team.id(), orgY.id(), "ACTIVE", "TEAM_APPLY", null,
                LocalDateTime.now());
        long otherInvite = insertMembershipRow(teamV.id(), orgY.id(), "PENDING", "ORG_INVITE", null,
                LocalDateTime.now());
        long maxId = ((Number) em.createNativeQuery("SELECT MAX(id) FROM team_org_memberships")
                .getSingleResult()).longValue();
        em.flush();

        Set<JsonNode> approveBodies = new HashSet<>();
        Set<JsonNode> rejectBodies = new HashSet<>();
        for (long id : List.of(otherApplication, otherActive, otherInvite, maxId + 1, maxId + 1000, 0L)) {
            approveBodies.add(errorOf(approve(xa, orgX.slug(), id, false, null)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString()));
            rejectBodies.add(errorOf(reject(xa, orgX.slug(), id, "理由", true)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString()));
        }
        assertThat(approveBodies).as("存在する他組織の行と存在しない ID の本文が同一").hasSize(1);
        assertThat(rejectBodies).hasSize(1);
        assertThat(approveBodies.iterator().next().get("code").asText()).isEqualTo("TEAM_070");
        assertThat(rejectBodies.iterator().next().get("code").asText()).isEqualTo("TEAM_070");

        assertThat(membershipRow(otherApplication)[0]).as("他組織の行は変わらない").isEqualTo("PENDING");
        assertThat(restrictionCount(teamU.id(), orgY.id())).isZero();
    }

    // =====================================================================
    // §6.4 判定表・AC-B14（逐次）
    // =====================================================================

    @Test
    @DisplayName("§6.4 既に ACTIVE の行・招待の行を承認・拒否すると 409 TEAM_071 で、行は変わらない")
    void 判定表_状態か向きが違えば409() throws Exception {
        TeamFx team2 = newTeam();
        long active = insertMembershipRow(team.id(), orgX.id(), "ACTIVE", "TEAM_APPLY", null, LocalDateTime.now());
        long invite = insertMembershipRow(team2.id(), orgX.id(), "PENDING", "ORG_INVITE", null, LocalDateTime.now());
        em.flush();

        for (long id : List.of(active, invite)) {
            approve(xa, orgX.slug(), id, false, null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
            reject(xa, orgX.slug(), id, null, null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        }
        assertThat(membershipRow(active)[0]).isEqualTo("ACTIVE");
        assertThat(membershipRow(invite)[0]).isEqualTo("PENDING");
        assertThat(restrictionCount(team2.id(), orgX.id())).isZero();
    }

    @Test
    @DisplayName("AC-B14 取下げ→承認の順なら承認は 404 TEAM_070。承認→取下げの順なら取下げは 409 TEAM_071")
    void 取下げと承認の判定表_逐次() throws Exception {
        TeamFx team2 = newTeam();
        long admin2 = newUser();
        makeTeamAdmin(admin2, team2.id());
        long withdrawnFirst = pendingApplication(team.id(), orgX.id(), null);
        long approvedFirst = pendingApplication(team2.id(), orgX.id(), null);
        em.flush();

        withdraw(ta, team.slug(), withdrawnFirst).andExpect(status().isNoContent());
        approve(xa, orgX.slug(), withdrawnFirst, false, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));

        approve(xa, orgX.slug(), approvedFirst, false, null).andExpect(status().isOk());
        withdraw(admin2, team2.slug(), approvedFirst)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        assertThat(membershipRow(approvedFirst)[0]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("拒否→承認の順なら承認は 404 TEAM_070、承認→拒否の順なら拒否は 409 TEAM_071")
    void 拒否と承認の判定表_逐次() throws Exception {
        TeamFx team2 = newTeam();
        long rejectedFirst = pendingApplication(team.id(), orgX.id(), null);
        long approvedFirst = pendingApplication(team2.id(), orgX.id(), null);

        reject(xa, orgX.slug(), rejectedFirst, null, null).andExpect(status().isOk());
        approve(xa, orgX.slug(), rejectedFirst, false, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));

        approve(xa, orgX.slug(), approvedFirst, false, null).andExpect(status().isOk());
        reject(xa, orgX.slug(), approvedFirst, null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
    }

    // =====================================================================
    // AC-C07・G103g 拒否
    // =====================================================================

    @Test
    @DisplayName("AC-C07 拒否すると 200 で行が消え、30日の冷却が返る。直後の再申請は 403 TEAM_068")
    void 拒否すると行が消え再申請は403() throws Exception {
        long id = applyAndGetId(ta, team.slug(), orgX.slug());

        MvcResult result = reject(xa, orgX.slug(), id, "今年度は受け付けていません", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction.kind").value("COOLDOWN"))
                .andExpect(jsonPath("$.data.restriction.restrictedUntil").isNotEmpty())
                .andReturn();
        assertThat(countMemberships(team.id(), orgX.id())).as("拒否で行は物理削除される").isZero();

        Object[] restriction = restrictionRow(team.id(), orgX.id());
        assertThat(restriction[0]).isEqualTo("COOLDOWN");
        assertThat(restriction[1]).isEqualTo("REJECTED");
        assertThat(restriction[2]).as("restricted_until").isNotNull();
        assertThat(json(result).at("/data/restriction/restrictedUntil").asText()).isNotBlank();

        apply(ta, team.slug(), orgX.slug())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_068"));
    }

    @Test
    @DisplayName("AC-G103g 拒否すると TEAM_ORG_APPLICATION_REJECTED が audit_logs に1行残り、理由と block が入る")
    void 拒否の監査ログ() throws Exception {
        long id = pendingApplication(team.id(), orgX.id(), null);

        reject(xa, orgX.slug(), id, "定員に達しました", true).andExpect(status().isOk());

        List<?> rows = auditRows("TEAM_ORG_APPLICATION_REJECTED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).isEqualTo(xa);
        assertThat(((Number) audit[2]).longValue()).isEqualTo(orgX.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(id);
        assertThat(metadata.get("reason").asText()).isEqualTo("定員に達しました");
        assertThat(metadata.get("block").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("拒否の理由は 500 文字で成功し、501 文字で 400（行は残る）")
    void 拒否の理由は500文字まで() throws Exception {
        TeamFx team2 = newTeam();
        long tooLong = pendingApplication(team.id(), orgX.id(), null);
        long ok = pendingApplication(team2.id(), orgX.id(), null);

        reject(xa, orgX.slug(), tooLong, "あ".repeat(501), false).andExpect(status().isBadRequest());
        assertThat(membershipRow(tooLong)[0]).isEqualTo("PENDING");
        reject(xa, orgX.slug(), ok, "あ".repeat(500), false).andExpect(status().isOk());
    }

    // =====================================================================
    // AC-C08・C09（拒否）・C10 制限
    // =====================================================================

    @Test
    @DisplayName("AC-C08 拒否から30日以内の再申請は 403 TEAM_068、30日を過ぎると 201（固定 Clock）")
    void 拒否の30日後に再申請できる() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);
        long id = applyAndGetId(ta, team.slug(), orgX.slug());

        reject(xa, orgX.slug(), id, null, false).andExpect(status().isOk());

        clock.advance(Duration.ofDays(29));
        apply(ta, team.slug(), orgX.slug())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_068"));

        clock.advance(Duration.ofDays(2));
        apply(ta, team.slug(), orgX.slug()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-C09 block=true で拒否すると無期限（BLOCK・期限 NULL）になり、30日後でも 403 TEAM_068")
    void ブロックは30日後も403() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);
        long id = applyAndGetId(ta, team.slug(), orgX.slug());

        reject(xa, orgX.slug(), id, null, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction.kind").value("BLOCK"))
                .andExpect(jsonPath("$.data.restriction.restrictedUntil").doesNotExist());
        Object[] restriction = restrictionRow(team.id(), orgX.id());
        assertThat(restriction[0]).isEqualTo("BLOCK");
        assertThat(restriction[2]).isNull();

        clock.advance(Duration.ofDays(31));
        apply(ta, team.slug(), orgX.slug())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_068"));
    }

    @Test
    @DisplayName("ブロック中の組み合わせを冷却付きで拒否しても、BLOCK は COOLDOWN で上書きされない（応答も BLOCK）")
    void ブロックは冷却で上書きされない() throws Exception {
        long first = pendingApplication(team.id(), orgX.id(), null);
        reject(xa, orgX.slug(), first, null, true).andExpect(status().isOk());
        // 制限中でも、制限の前から届いていた申請という想定で行を直接作る
        long second = pendingApplication(team.id(), orgX.id(), null);

        reject(xa, orgX.slug(), second, null, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction.kind").value("BLOCK"));
        assertThat(restrictionRow(team.id(), orgX.id())[0]).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("AC-C10 冷却中とブロック中で、再申請のステータス・エラーコード・メッセージが同じ")
    void 冷却とブロックは区別できない() throws Exception {
        TeamFx team2 = newTeam();
        long admin2 = newUser();
        makeTeamAdmin(admin2, team2.id());
        em.flush();
        long cooled = applyAndGetId(ta, team.slug(), orgX.slug());
        long blocked = applyAndGetId(admin2, team2.slug(), orgX.slug());
        reject(xa, orgX.slug(), cooled, null, false).andExpect(status().isOk());
        reject(xa, orgX.slug(), blocked, null, true).andExpect(status().isOk());

        MvcResult cooling = apply(ta, team.slug(), orgX.slug()).andReturn();
        MvcResult blocking = apply(admin2, team2.slug(), orgX.slug()).andReturn();

        assertThat(cooling.getResponse().getStatus()).isEqualTo(403);
        assertThat(blocking.getResponse().getStatus()).isEqualTo(cooling.getResponse().getStatus());
        assertThat(errorOf(blocking.getResponse().getContentAsString()))
                .isEqualTo(errorOf(cooling.getResponse().getContentAsString()));
        assertThat(errorOf(cooling.getResponse().getContentAsString()).get("code").asText()).isEqualTo("TEAM_068");
    }

    // =====================================================================
    // AC-G141・G129 受信申請一覧
    // =====================================================================

    @Test
    @DisplayName("AC-G141 受信申請一覧は requestedAt の降順・size 既定20・上限100で、他組織の申請・招待・加盟済みは含まない")
    void 一覧は申請日時の降順でページングされる() throws Exception {
        TeamFx oldest = newTeam();
        TeamFx middle = newTeam();
        TeamFx newest = newTeam();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 9, 0);
        insertMembershipRow(oldest.id(), orgX.id(), "PENDING", "TEAM_APPLY", null, base);
        insertMembershipRow(newest.id(), orgX.id(), "PENDING", "TEAM_APPLY", groupA, base.plusDays(2));
        insertMembershipRow(middle.id(), orgX.id(), "PENDING", "TEAM_APPLY", groupB, base.plusDays(1));
        insertMembershipRow(newTeam().id(), orgY.id(), "PENDING", "TEAM_APPLY", null, base);
        insertMembershipRow(newTeam().id(), orgX.id(), "PENDING", "ORG_INVITE", null, base);
        insertMembershipRow(newTeam().id(), orgX.id(), "ACTIVE", "TEAM_APPLY", null, base);
        em.flush();

        MvcResult result = list(xa, orgX.slug(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andReturn();
        List<String> slugs = new ArrayList<>();
        json(result).get("data").forEach(n -> slugs.add(n.get("team").get("slug").asText()));
        assertThat(slugs).containsExactly(newest.slug(), middle.slug(), oldest.slug());
        json(result).get("data").forEach(n -> {
            assertThat(n.get("status").asText()).isEqualTo("PENDING");
            assertThat(n.get("direction").asText()).isEqualTo("TEAM_APPLY");
            assertThat(n.get("organization").get("slug").asText()).isEqualTo(orgX.slug());
        });

        list(xa, orgX.slug(), "?size=1000").andExpect(jsonPath("$.meta.size").value(100));
        list(xa, orgX.slug(), "?size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        MvcResult filtered = list(xa, orgX.slug(), "?teamGroupId=" + groupA).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andReturn();
        assertThat(json(filtered).get("data").get(0).get("team").get("slug").asText()).isEqualTo(newest.slug());
        assertThat(json(filtered).get("data").get(0).get("teamGroup").get("name").asText()).isEqualTo("グループA");
    }

    @Test
    @DisplayName("AC-G129 受信申請一覧の SQL 本数は、行数に比例して増えない（1件と8件で同じ）")
    void 一覧のSQL本数は行数に比例しない() throws Exception {
        insertMembershipRow(newTeam().id(), orgX.id(), "PENDING", "TEAM_APPLY", groupA, LocalDateTime.now());
        em.flush();
        em.clear();
        list(xa, orgX.slug(), "").andExpect(status().isOk()); // ウォームアップ

        Statistics stats = statisticsCleared();
        list(xa, orgX.slug(), "").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        long oneRow = stats.getPrepareStatementCount();

        for (int i = 0; i < 7; i++) {
            insertMembershipRow(newTeam().id(), orgX.id(), "PENDING", "TEAM_APPLY", i % 2 == 0 ? groupA : groupB,
                    LocalDateTime.now().plusMinutes(i + 1));
        }
        em.flush();
        em.clear();
        stats = statisticsCleared();
        list(xa, orgX.slug(), "").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(8));
        long eightRows = stats.getPrepareStatementCount();

        assertThat(eightRows).as("1件のとき %d 本・8件のとき %d 本", oneRow, eightRows).isEqualTo(oneRow);
    }

    // =====================================================================
    // AC-C04 複数組織への加盟（統合確認）
    // =====================================================================

    @Test
    @DisplayName("AC-C04 Y に加盟済みの T の X への申請を承認すると、(a)両組織の加盟チーム一覧 (b)T の所属組織2件 "
            + "(c)T の ORGANIZATION_WIDE 予定を X・Y 両方のメンバーが実際の閲覧 API で読める (d)/me/teams に X・Y が出る")
    void 複数組織への加盟が成立する() throws Exception {
        insertMembershipRow(team.id(), orgY.id(), "ACTIVE", "ORG_INVITE", null, LocalDateTime.now().minusDays(10));
        long id = pendingApplication(team.id(), orgX.id(), groupA);
        long yMember = newUser();
        makeOrgMember(yMember, orgY.id());
        OrgFx orgZ = newOrg();
        long zMember = newUser();
        makeOrgMember(zMember, orgZ.id());
        long organizationWide = saveOrganizationWideSchedule(team.id(), ta);

        // 承認の前: Y のメンバーは読めるが、X のメンバー（XM）はまだ T の親組織のメンバーではないので読めない
        // （承認が無ければ (c) は落ちることを、同じテストの中で確かめる）
        readSchedule(yMember, organizationWide).andExpect(status().isOk());
        readSchedule(xm, organizationWide).andExpect(status().isForbidden());

        approve(xa, orgX.slug(), id, false, null).andExpect(status().isOk());
        em.flush();
        em.clear();

        // (a) 両組織の加盟チーム一覧に T が出る
        for (Object[] pair : List.of(new Object[]{orgX.slug(), xa}, new Object[]{orgY.slug(), ya})) {
            MvcResult teams = mockMvc.perform(get("/api/v1/organizations/{slug}/teams", pair[0])
                            .with(user(String.valueOf(pair[1]))))
                    .andExpect(status().isOk()).andReturn();
            List<String> teamSlugs = new ArrayList<>();
            json(teams).get("data").forEach(n -> teamSlugs.add(n.get("slug").asText()));
            assertThat(teamSlugs).as("組織 %s の加盟チーム一覧", pair[0]).contains(team.slug());
        }

        // (b) T の所属組織に X と Y の2件が出る
        MvcResult orgs = mockMvc.perform(get("/api/v1/teams/{slug}/organizations", team.slug())
                        .with(user(String.valueOf(ta))))
                .andExpect(status().isOk()).andReturn();
        List<String> orgSlugs = new ArrayList<>();
        json(orgs).get("data").forEach(n -> orgSlugs.add(n.get("slug").asText()));
        assertThat(orgSlugs).containsExactlyInAnyOrder(orgX.slug(), orgY.slug());

        // (c) T の ORGANIZATION_WIDE 予定（親組織のメンバーへ公開）を、X・Y 両方のメンバーが実際の閲覧 API で読める。
        //     無関係の組織 Z のメンバーは読めない（閲覧判定が素通しでないことの対照）
        readSchedule(xm, organizationWide).andExpect(status().isOk());
        readSchedule(yMember, organizationWide).andExpect(status().isOk());
        readSchedule(zMember, organizationWide).andExpect(status().isForbidden());
        Map<ScopeKey, Set<Long>> parents =
                scopeAncestorResolver.resolveParentOrgIds(Set.of(new ScopeKey("TEAM", team.id())));
        assertThat(parents.get(new ScopeKey("TEAM", team.id()))).containsExactlyInAnyOrder(orgX.id(), orgY.id());

        // (d) /me/teams の T の要素に X と Y の両方の組織が含まれる
        MvcResult me = mockMvc.perform(get("/api/v1/me/teams").with(user(String.valueOf(ta))))
                .andExpect(status().isOk()).andReturn();
        List<Long> parentIds = new ArrayList<>();
        for (JsonNode node : json(me).get("data")) {
            if (team.slug().equals(node.get("slug").asText())) {
                node.get("organizations").forEach(o -> parentIds.add(o.get("id").asLong()));
            }
        }
        assertThat(parentIds).containsExactlyInAnyOrder(orgX.id(), orgY.id());
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private void makeOrgDeputy(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, orgId);
    }

    private void makeOrgMember(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
    }

    /** チーム T の ORGANIZATION_WIDE（予定では visibility=ORGANIZATION）の予定を作る。 */
    private long saveOrganizationWideSchedule(long teamId, long authorId) {
        long scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("加盟承認後の組織公開予定")
                .startAt(LocalDateTime.of(2026, 11, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 11, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.ORGANIZATION)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(authorId)
                .build()).getId();
        em.flush();
        em.clear();
        return scheduleId;
    }

    /** 実際の閲覧 API（実 Security・実認可）で T の予定を読む。 */
    private ResultActions readSchedule(long viewer, long scheduleId) throws Exception {
        em.flush();
        em.clear();
        return mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", team.slug(), scheduleId)
                .with(user(String.valueOf(viewer))));
    }

    /** PENDING / TEAM_APPLY の申請行を、検証対象の API を使わずに作る。 */
    private long pendingApplication(long teamId, long orgId, UUID groupId) {
        long id = insertMembershipRow(teamId, orgId, "PENDING", "TEAM_APPLY", groupId, LocalDateTime.now());
        em.flush();
        return id;
    }

    private ResultActions approve(long actor, String orgSlug, long membershipId, Boolean overrideGroup,
                                  UUID groupId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (overrideGroup != null) {
            body.put("overrideGroup", overrideGroup);
        }
        body.put("groupId", groupId == null ? null : groupId.toString());
        return mockMvc.perform(post(APPLICATIONS + "/{id}/approve", orgSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions reject(long actor, String orgSlug, long membershipId, String reason, Boolean block)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (block != null) {
            body.put("block", block);
        }
        return mockMvc.perform(post(APPLICATIONS + "/{id}/reject", orgSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions list(long actor, String orgSlug, String query) throws Exception {
        return mockMvc.perform(get(APPLICATIONS + query, orgSlug).with(user(String.valueOf(actor))));
    }

    private ResultActions apply(long actor, String teamSlug, String orgSlug) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("organizationSlug", orgSlug))));
    }

    private long applyAndGetId(long actor, String teamSlug, String orgSlug) throws Exception {
        MvcResult result = apply(actor, teamSlug, orgSlug).andExpect(status().isCreated()).andReturn();
        return json(result).get("data").get("id").asLong();
    }

    private ResultActions withdraw(long actor, String teamSlug, long membershipId) throws Exception {
        return mockMvc.perform(delete("/api/v1/teams/{teamSlug}/org-applications/{id}", teamSlug, membershipId)
                .with(user(String.valueOf(actor))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 応答本文の error 部分（ステータス以外に区別の手掛かりが無いことを比べる）。 */
    private JsonNode errorOf(String body) throws Exception {
        return objectMapper.readTree(body).get("error");
    }

    /** status, message, responded_by, responded_at。 */
    private Object[] membershipRow(long id) {
        return (Object[]) em.createNativeQuery(
                        "SELECT status, message, responded_by, responded_at FROM team_org_memberships WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private UUID groupIdOf(long id) {
        Object raw = em.createNativeQuery("SELECT BIN_TO_UUID(group_id) FROM team_org_memberships WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        return raw == null ? null : UUID.fromString(String.valueOf(raw));
    }

    /** kind, reason, restricted_until。 */
    private Object[] restrictionRow(long teamId, long orgId) {
        return (Object[]) em.createNativeQuery(
                        "SELECT kind, reason, restricted_until FROM team_org_affiliation_restrictions "
                                + "WHERE team_id = :teamId AND organization_id = :orgId AND direction = 'TEAM_APPLY'")
                .setParameter("teamId", teamId)
                .setParameter("orgId", orgId)
                .getSingleResult();
    }

    private long restrictionCount(long teamId, long orgId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM team_org_affiliation_restrictions "
                                + "WHERE team_id = :teamId AND organization_id = :orgId")
                .setParameter("teamId", teamId)
                .setParameter("orgId", orgId)
                .getSingleResult()).longValue();
    }

    private List<?> auditRows(String eventType) {
        return em.createNativeQuery(
                        "SELECT user_id, team_id, organization_id, metadata FROM audit_logs "
                                + "WHERE event_type = :type AND team_id = :teamId")
                .setParameter("type", eventType)
                .setParameter("teamId", team.id())
                .getResultList();
    }

    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    /** 制限の判定・記録に使う時計を、進められる固定時計に差し替える（AC-C08・C09）。 */
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
