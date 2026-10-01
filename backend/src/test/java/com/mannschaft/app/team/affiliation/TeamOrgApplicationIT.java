package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * F01.2.1 部隊 2-B1 — チームから組織への加盟申請・取下げ・申請中一覧の統合テスト（試練）。
 *
 * <p>実 MySQL（Testcontainers）・実 Security フィルタ・MockMvc で実 API を叩く。認可（{@code AccessControlService}）・
 * Service・Repository・組織ドメインの窓口はモックしない。{@code @Transactional} でテストごとにロールバックする
 * （並行・コミットを要する検証は {@link TeamOrgApplicationCommittedIT}）。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える: TA＝チーム ADMIN、TD＝チーム DEPUTY_ADMIN（付与なし）、
 * TM＝チーム MEMBER（付与なし）、TG＝権限グループで付与済みのチーム MEMBER、XA＝組織 ADMIN。</p>
 *
 * <h2>通知について（6-D 依存）</h2>
 * <p>申請と同じトランザクションで fan-out ジョブが enqueue されることを、ジョブ行
 * （受信者の解決方式・{@code scope_ref}・{@code action_url}・{@code source_type}/{@code source_id}）で確かめる。
 * 通知の基盤（{@code FanoutEnqueueCommand} 版の {@code enqueueInCurrentTransaction}・受信者ソース 2 種）は 6-D の PR で
 * 着地する。6-D が main に入るまでは通知に関わるテストが red になりうる。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-B1 チームの加盟申請・取下げ・申請中一覧")
class TeamOrgApplicationIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamOrgAffiliationRestrictionService restrictionService;

    private TeamFx team;
    private OrgFx org;
    private long ta;
    private long td;
    private long tm;
    private long tg;
    private long xa;

    /** 差し替えた Clock を元に戻すための退避。 */
    private Object originalRestrictionClock;

    @BeforeEach
    void setUp() {
        seedAffiliationPermission();
        team = newTeam();
        org = newOrg();
        ta = newUser();
        td = newUser();
        tm = newUser();
        tg = newUser();
        xa = newUser();
        makeTeamAdmin(ta, team.id());
        makeTeamDeputy(td, team.id());
        makeTeamMember(tm, team.id());
        makeTeamMember(tg, team.id());
        grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
        makeOrgAdmin(xa, org.id());
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
    // AC-B01 申請 / AC-G117a・AC-G103b
    // =====================================================================

    @Test
    @DisplayName("AC-B01 申請すると 201 で PENDING/TEAM_APPLY の行が1件でき、共通表現が返る")
    void 申請すると201で行ができる() throws Exception {
        MvcResult result = apply(ta, team.slug(), org.slug(), null, "よろしくお願いします")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.direction").value("TEAM_APPLY"))
                .andExpect(jsonPath("$.data.team.slug").value(team.slug()))
                .andExpect(jsonPath("$.data.organization.slug").value(org.slug()))
                .andExpect(jsonPath("$.data.organization.name").value(org.name()))
                .andExpect(jsonPath("$.data.message").value("よろしくお願いします"))
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist())
                .andExpect(jsonPath("$.data.requestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn();

        JsonNode data = json(result).get("data");
        assertThat(data.get("requestedBy").get("id").asLong()).isEqualTo(ta);
        assertThat(data.get("requestedBy").get("displayName").asText()).isNotBlank();
        long membershipId = data.get("id").asLong();
        assertThat(countMemberships(team.id(), org.id())).isEqualTo(1);
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT status, direction, invited_by FROM team_org_memberships WHERE id = :id")
                .setParameter("id", membershipId).getSingleResult();
        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isEqualTo("TEAM_APPLY");
        assertThat(((Number) row[2]).longValue()).isEqualTo(ta);
    }

    @Test
    @DisplayName("AC-G103b 申請すると TEAM_ORG_APPLICATION_SUBMITTED が audit_logs に1行残る")
    void 申請の監査ログが残る() throws Exception {
        long membershipId = applyAndGetId(ta, team.slug(), org.slug());

        List<?> rows = auditRows("TEAM_ORG_APPLICATION_SUBMITTED");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).as("操作者").isEqualTo(ta);
        assertThat(((Number) audit[2]).longValue()).as("組織コンテキスト").isEqualTo(org.id());
        JsonNode metadata = objectMapper.readTree(String.valueOf(audit[3]));
        assertThat(metadata.get("membership_id").asLong()).isEqualTo(membershipId);
    }

    // =====================================================================
    // AC-B02 受付 off / AC-B18(負の側)
    // =====================================================================

    @Test
    @DisplayName("AC-B02 受付 off の組織へ直接申請すると 403 TEAM_064 で、行は作られない")
    void 受付offは403() throws Exception {
        OrgFx closed = newOrg(false, false, "OFF", "PUBLIC");

        apply(ta, team.slug(), closed.slug(), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_064"));

        assertThat(countMemberships(team.id(), closed.id())).isZero();
    }

    // =====================================================================
    // AC-B04 / AC-P02〜P04 加盟操作者
    // =====================================================================

    @Test
    @DisplayName("AC-B04 TM・TD（付与なし）が申請すると 403、TG（権限グループで付与）が申請すると 201")
    void 付与のない人は403_付与された人は201() throws Exception {
        apply(tm, team.slug(), org.slug(), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        apply(td, team.slug(), org.slug(), null, null).andExpect(status().isForbidden());
        assertThat(countMemberships(team.id(), org.id())).as("403 のときは行を作らない").isZero();

        apply(tg, team.slug(), org.slug(), null, null).andExpect(status().isCreated());
        assertThat(countMemberships(team.id(), org.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-P02 TD・TM は、申請中一覧の GET・取下げの DELETE も 403（行は消えない）")
    void 付与のない人は一覧も取下げも403() throws Exception {
        long membershipId = applyAndGetId(ta, team.slug(), org.slug());

        for (long actor : List.of(td, tm)) {
            list(actor, team.slug(), "").andExpect(status().isForbidden());
            withdraw(actor, team.slug(), membershipId).andExpect(status().isForbidden());
        }
        assertThat(countMemberships(team.id(), org.id())).as("403 の取下げでは行が消えない").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-P03 DEPUTY_ADMIN 向けの権限グループで付与すると、TD が申請・一覧・取下げをできる（付与のない TD は 403）")
    void 付与された副管理者は操作できる() throws Exception {
        long grantedDeputy = newUser();
        makeTeamDeputy(grantedDeputy, team.id());
        grantAffiliationByPermissionGroup(grantedDeputy, team.id(), "DEPUTY_ADMIN");
        em.flush();
        em.clear();

        apply(td, team.slug(), org.slug(), null, null).andExpect(status().isForbidden());

        long membershipId = applyAndGetId(grantedDeputy, team.slug(), org.slug());
        list(grantedDeputy, team.slug(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        withdraw(grantedDeputy, team.slug(), membershipId).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-P04 MEMBER 向けの権限グループで付与された TG は操作でき、付与のない TM は引き続き 403")
    void 付与されたメンバーは操作でき付与のないメンバーは403() throws Exception {
        long membershipId = applyAndGetId(tg, team.slug(), org.slug());
        list(tg, team.slug(), "").andExpect(status().isOk());
        withdraw(tg, team.slug(), membershipId).andExpect(status().isNoContent());

        list(tm, team.slug(), "").andExpect(status().isForbidden());
        apply(tm, team.slug(), newOrg().slug(), null, null).andExpect(status().isForbidden());
    }

    // =====================================================================
    // AC-B05 見えない組織は存在しない slug と同じ 404（存在オラクルなし）
    // =====================================================================

    @Test
    @DisplayName("AC-B05 見えない非公開組織・アーカイブ済み組織への申請は、存在しない slug と同じステータス・同じ本文の 404")
    void 見えない組織は存在しないslugと同じ404() throws Exception {
        OrgFx privateOrg = newOrg(true, false, "OFF", "PRIVATE");
        OrgFx archivedOrg = newOrg();
        archiveOrganization(archivedOrg.id());
        em.flush();
        em.clear();

        MvcResult missing = apply(ta, team.slug(), "af-o-no-such-organization", null, null)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult invisible = apply(ta, team.slug(), privateOrg.slug(), null, null)
                .andExpect(status().isNotFound()).andReturn();
        MvcResult archived = apply(ta, team.slug(), archivedOrg.slug(), null, null)
                .andExpect(status().isNotFound()).andReturn();

        String missingBody = missing.getResponse().getContentAsString();
        assertThat(invisible.getResponse().getContentAsString())
                .as("非公開組織の 404 は存在しない slug と本文まで一致する（TEAM_064・TEAM_068 等を返さない）")
                .isEqualTo(missingBody);
        assertThat(archived.getResponse().getContentAsString()).isEqualTo(missingBody);
        assertThat(countMemberships(team.id(), privateOrg.id())).isZero();
    }

    // =====================================================================
    // AC-B06・B07(逐次)・B08 重複
    // =====================================================================

    @Test
    @DisplayName("AC-B06 加盟済みの組み合わせで申請すると 409 TEAM_065")
    void 加盟済みは409_TEAM_065() throws Exception {
        insertMembershipRow(team.id(), org.id(), "ACTIVE", "ORG_INVITE", null, LocalDateTime.now());
        em.flush();

        apply(ta, team.slug(), org.slug(), null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TEAM_065"));
    }

    @Test
    @DisplayName("AC-B07(逐次) 2回目の申請は 409 TEAM_066 で、行は1件のまま")
    void 二回目の申請は409_TEAM_066() throws Exception {
        apply(ta, team.slug(), org.slug(), null, null).andExpect(status().isCreated());

        apply(ta, team.slug(), org.slug(), null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TEAM_066"));
        assertThat(countMemberships(team.id(), org.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-B08 組織から招待が届いている状態で申請すると 409 TEAM_066")
    void 招待が届いている状態の申請は409_TEAM_066() throws Exception {
        insertMembershipRow(team.id(), org.id(), "PENDING", "ORG_INVITE", null, LocalDateTime.now());
        em.flush();

        apply(ta, team.slug(), org.slug(), null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TEAM_066"));
        assertThat(countMemberships(team.id(), org.id())).as("招待の行はそのまま").isEqualTo(1);
    }

    // =====================================================================
    // AC-B09 同時申請の上限
    // =====================================================================

    @Test
    @DisplayName("AC-B09 同時申請の上限: 10件目は成功し、11件目は 422 TEAM_069（行は作られない）")
    void 同時申請の上限は10件() throws Exception {
        for (int i = 0; i < 9; i++) {
            insertMembershipRow(team.id(), newOrg().id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
        }
        em.flush();
        OrgFx tenth = newOrg();
        OrgFx eleventh = newOrg();

        apply(ta, team.slug(), tenth.slug(), null, null).andExpect(status().isCreated());

        apply(ta, team.slug(), eleventh.slug(), null, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TEAM_069"));
        assertThat(countMemberships(team.id(), eleventh.id())).isZero();
    }

    // =====================================================================
    // AC-B10〜B12 グループの検証
    // =====================================================================

    @Test
    @DisplayName("AC-B10 REQUIRED の組織へ group_id なしで申請すると 400 TEAM_067、生存グループを付ければ 201 でグループ名が返る")
    void REQUIREDはgroupId必須() throws Exception {
        OrgFx required = newOrg(true, true, "REQUIRED", "PUBLIC");
        UUID group = newGroup(required.id(), "平成20年度卒", false);
        em.flush();

        apply(ta, team.slug(), required.slug(), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAM_067"));
        assertThat(countMemberships(team.id(), required.id())).isZero();

        apply(ta, team.slug(), required.slug(), group, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamGroup.name").value("平成20年度卒"))
                .andExpect(jsonPath("$.data.teamGroup.id").value(group.toString()));
    }

    @Test
    @DisplayName("AC-B11 グループ選択が OFF の組織へ group_id 付きで申請すると 400 TEAM_072（黙って捨てない）")
    void OFFでgroupId付きは400_TEAM_072() throws Exception {
        UUID someGroup = UUID.randomUUID();

        apply(ta, team.slug(), org.slug(), someGroup, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAM_072"));
        assertThat(countMemberships(team.id(), org.id())).isZero();
    }

    @Test
    @DisplayName("AC-B11 グループ機能が off の組織は、保存値が REQUIRED でも実効 OFF として扱う（group_id なしで 201・付ければ 400）")
    void グループ機能offは保存値にかかわらず実効OFF() throws Exception {
        OrgFx groupsOff = newOrg(true, false, "REQUIRED", "PUBLIC");
        UUID someGroup = newGroup(groupsOff.id(), "残っているグループ", false);
        em.flush();

        apply(ta, team.slug(), groupsOff.slug(), someGroup, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAM_072"));
        apply(ta, team.slug(), groupsOff.slug(), null, null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-B12 他組織のグループ・削除済みグループを付けて申請すると 400 TEAM_072。OPTIONAL で未指定なら 201")
    void 他組織や削除済みのグループは400_TEAM_072() throws Exception {
        OrgFx optional = newOrg(true, true, "OPTIONAL", "PUBLIC");
        OrgFx other = newOrg(true, true, "OPTIONAL", "PUBLIC");
        UUID othersGroup = newGroup(other.id(), "他組織のグループ", false);
        UUID deletedGroup = newGroup(optional.id(), "削除済みのグループ", true);
        em.flush();

        apply(ta, team.slug(), optional.slug(), othersGroup, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAM_072"));
        apply(ta, team.slug(), optional.slug(), deletedGroup, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TEAM_072"));
        assertThat(countMemberships(team.id(), optional.id())).isZero();

        apply(ta, team.slug(), optional.slug(), null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.teamGroup").doesNotExist());
    }

    // =====================================================================
    // AC-B13・G103h・G109(IT) 取下げと再申請の抑止
    // =====================================================================

    @Test
    @DisplayName("AC-B13 取下げると 204。直後の再申請は 403 TEAM_068、24時間後は 201（固定 Clock）")
    void 取下げの24時間後に再申請できる() throws Exception {
        MutableClock clock = new MutableClock(Instant.now());
        swapRestrictionClock(clock);

        long membershipId = applyAndGetId(ta, team.slug(), org.slug());
        withdraw(ta, team.slug(), membershipId).andExpect(status().isNoContent());
        assertThat(countMemberships(team.id(), org.id())).as("取下げで行は物理削除される").isZero();

        apply(ta, team.slug(), org.slug(), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_068"));

        clock.advance(Duration.ofHours(23));
        apply(ta, team.slug(), org.slug(), null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TEAM_068"));

        clock.advance(Duration.ofHours(2));
        apply(ta, team.slug(), org.slug(), null, null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("AC-G103h 取下げると TEAM_ORG_APPLICATION_WITHDRAWN が audit_logs に1行残る")
    void 取下げの監査ログが残る() throws Exception {
        long membershipId = applyAndGetId(ta, team.slug(), org.slug());

        withdraw(ta, team.slug(), membershipId).andExpect(status().isNoContent());

        List<?> rows = auditRows("TEAM_ORG_APPLICATION_WITHDRAWN");
        assertThat(rows).hasSize(1);
        Object[] audit = (Object[]) rows.get(0);
        assertThat(((Number) audit[0]).longValue()).isEqualTo(ta);
        assertThat(objectMapper.readTree(String.valueOf(audit[3])).get("membership_id").asLong())
                .isEqualTo(membershipId);
    }

    @Test
    @DisplayName("取下げの判定表: 存在しない ID は 404 TEAM_070、ACTIVE になった行・招待の行は 409 TEAM_071")
    void 取下げの判定表() throws Exception {
        OrgFx activeOrg = newOrg();
        OrgFx inviteOrg = newOrg();
        long activeId = insertMembershipRow(team.id(), activeOrg.id(), "ACTIVE", "TEAM_APPLY", null,
                LocalDateTime.now());
        long inviteId = insertMembershipRow(team.id(), inviteOrg.id(), "PENDING", "ORG_INVITE", null,
                LocalDateTime.now());
        em.flush();

        withdraw(ta, team.slug(), 987_654_321L)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TEAM_070"));
        withdraw(ta, team.slug(), activeId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        withdraw(ta, team.slug(), inviteId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEAM_071"));
        assertThat(countMemberships(team.id(), activeOrg.id())).as("409 のときは行を消さない").isEqualTo(1);
        assertThat(countMemberships(team.id(), inviteOrg.id())).isEqualTo(1);
    }

    // =====================================================================
    // AC-B15・G104 message
    // =====================================================================

    @Test
    @DisplayName("AC-B15 message は 500 文字で成功し、501 文字で 400")
    void messageは500文字まで() throws Exception {
        OrgFx ok = newOrg();
        OrgFx tooLong = newOrg();

        apply(ta, team.slug(), ok.slug(), null, "あ".repeat(500)).andExpect(status().isCreated());
        apply(ta, team.slug(), tooLong.slug(), null, "あ".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(countMemberships(team.id(), tooLong.id())).isZero();
    }

    @Test
    @DisplayName("AC-B15 サロゲートペア（絵文字）はコードポイントで数える（500個で成功・501個で 400）")
    void messageはコードポイントで数える() throws Exception {
        OrgFx ok = newOrg();
        OrgFx tooLong = newOrg();

        apply(ta, team.slug(), ok.slug(), null, "😀".repeat(500)).andExpect(status().isCreated());
        apply(ta, team.slug(), tooLong.slug(), null, "😀".repeat(501)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-G104 message が空文字・空白だけなら null に正規化して保存する（PII を残さない）")
    void 空のmessageはnullに正規化される() throws Exception {
        OrgFx empty = newOrg();
        OrgFx blank = newOrg();

        long emptyId = applyAndGetId(ta, team.slug(), empty.slug(), "");
        long blankId = applyAndGetId(ta, team.slug(), blank.slug(), "   ");

        for (long id : List.of(emptyId, blankId)) {
            Object message = em.createNativeQuery("SELECT message FROM team_org_memberships WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
            assertThat(message).as("空の添え書きは NULL で保存される").isNull();
        }
    }

    // =====================================================================
    // AC-B16
    // =====================================================================

    @Test
    @DisplayName("AC-B16 他組織に加盟済みのチームも申請できる（複数組織への同時加盟）")
    void 他組織に加盟済みでも申請できる() throws Exception {
        OrgFx joined = newOrg();
        insertMembershipRow(team.id(), joined.id(), "ACTIVE", "ORG_INVITE", null, LocalDateTime.now());
        em.flush();

        apply(ta, team.slug(), org.slug(), null, null).andExpect(status().isCreated());
    }

    // =====================================================================
    // AC-G141・G129 一覧
    // =====================================================================

    @Test
    @DisplayName("AC-G141 申請中一覧は requestedAt の降順で、size は既定 20・上限 100 で返る（他チームの申請は含まない）")
    void 一覧は申請日時の降順でページングされる() throws Exception {
        OrgFx oldest = newOrg();
        OrgFx middle = newOrg();
        OrgFx newest = newOrg();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 9, 0);
        insertMembershipRow(team.id(), oldest.id(), "PENDING", "TEAM_APPLY", null, base);
        insertMembershipRow(team.id(), newest.id(), "PENDING", "TEAM_APPLY", null, base.plusDays(2));
        insertMembershipRow(team.id(), middle.id(), "PENDING", "TEAM_APPLY", null, base.plusDays(1));
        // 他チームの申請・招待・加盟は含まれない
        TeamFx otherTeam = newTeam();
        insertMembershipRow(otherTeam.id(), newOrg().id(), "PENDING", "TEAM_APPLY", null, base);
        insertMembershipRow(team.id(), newOrg().id(), "PENDING", "ORG_INVITE", null, base);
        insertMembershipRow(team.id(), newOrg().id(), "ACTIVE", "TEAM_APPLY", null, base);
        em.flush();

        MvcResult result = list(ta, team.slug(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andReturn();
        List<String> slugs = new ArrayList<>();
        json(result).get("data").forEach(n -> slugs.add(n.get("organization").get("slug").asText()));
        assertThat(slugs).containsExactly(newest.slug(), middle.slug(), oldest.slug());

        list(ta, team.slug(), "?size=1000").andExpect(jsonPath("$.meta.size").value(100));
        list(ta, team.slug(), "?size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("AC-G129 申請中一覧の SQL 本数は、行数に比例して増えない（1件と8件で同じ）")
    void 一覧のSQL本数は行数に比例しない() throws Exception {
        UUID groupId = null;
        OrgFx first = newOrg(true, true, "OPTIONAL", "PUBLIC");
        groupId = newGroup(first.id(), "グループ", false);
        insertMembershipRow(team.id(), first.id(), "PENDING", "TEAM_APPLY", groupId, LocalDateTime.now());
        em.flush();
        em.clear();
        list(ta, team.slug(), "").andExpect(status().isOk()); // ウォームアップ（初回のメタデータ・キャッシュ）

        Statistics stats = statisticsCleared();
        list(ta, team.slug(), "").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        long oneRow = stats.getPrepareStatementCount();

        for (int i = 0; i < 7; i++) {
            OrgFx another = newOrg(true, true, "OPTIONAL", "PUBLIC");
            UUID anotherGroup = newGroup(another.id(), "グループ" + i, false);
            insertMembershipRow(team.id(), another.id(), "PENDING", "TEAM_APPLY", anotherGroup,
                    LocalDateTime.now().plusMinutes(i));
        }
        em.flush();
        em.clear();
        stats = statisticsCleared();
        list(ta, team.slug(), "").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(8));
        long eightRows = stats.getPrepareStatementCount();

        assertThat(eightRows).as("1件のとき %d 本・8件のとき %d 本", oneRow, eightRows).isEqualTo(oneRow);
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    private ResultActions apply(long actor, String teamSlug, String organizationSlug, UUID groupId,
                                String message) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationSlug", organizationSlug);
        body.put("groupId", groupId == null ? null : groupId.toString());
        body.put("message", message);
        return mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long applyAndGetId(long actor, String teamSlug, String organizationSlug) throws Exception {
        return applyAndGetId(actor, teamSlug, organizationSlug, null);
    }

    private long applyAndGetId(long actor, String teamSlug, String organizationSlug, String message)
            throws Exception {
        MvcResult result = apply(actor, teamSlug, organizationSlug, null, message)
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("data").get("id").asLong();
    }

    private ResultActions list(long actor, String teamSlug, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/teams/{teamSlug}/org-applications" + query, teamSlug)
                .with(user(String.valueOf(actor))));
    }

    private ResultActions withdraw(long actor, String teamSlug, long membershipId) throws Exception {
        return mockMvc.perform(delete("/api/v1/teams/{teamSlug}/org-applications/{id}", teamSlug, membershipId)
                .with(user(String.valueOf(actor))));
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

    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    /** 制限の判定・記録に使う時計を、進められる固定時計に差し替える（AC-B13）。 */
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
