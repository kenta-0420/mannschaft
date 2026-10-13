package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-B（試練・red）— 範囲テンプレートのグループ3項目（AC-J01〜J03・G123）。
 *
 * <p>実 MySQL＋実 Security フィルタ＋ MockMvc。認可・Service・Repository はモックしない。
 * 前提（設計書 §16 J）: 組織X に G1〜G4（sort_order 10・20・30・40）と各グループのチーム T1〜T4、未分類 T0。
 * テンプレートは API（POST/PUT/GET {@code /api/v1/organizations/{orgId}/announcement-templates}）で保存し、
 * 保存後にグループを足す・並べ替える・削除してから、{@code templateId} だけを指定して送る。</p>
 *
 * <p>JSON の項目名は broadcast と揃える: {@code targetGroupIds}（UUID 文字列の配列）・
 * {@code targetGroupRange}（{@code {fromGroupId,toGroupId}}）・{@code includeUnassigned}。
 * 警告は応答の {@code data.warnings}（文字列配列。{@code TEMPLATE_GROUPS_REMOVED} を含む）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-B 範囲テンプレートのグループ項目（AC-J01〜J03・G123）")
class RangeTemplateGroupAudienceIT extends AbstractBroadcastAudienceIT {

    private static final String ORG_TEMPLATES = "/api/v1/organizations/{orgId}/announcement-templates";
    private static final String ORG_TEMPLATE = "/api/v1/organizations/{orgId}/announcement-templates/{id}";
    /** MANAGE_CONTENT を持つ DEPUTY_ADMIN（設計書 §16 の XD1）。 */
    private static final Long XD1 = 940602001L;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private OrganizationEntity orgX;
    private OrganizationEntity orgY;
    private OrgTeamGroupEntity g1;
    private OrgTeamGroupEntity g2;
    private OrgTeamGroupEntity g3;
    private OrgTeamGroupEntity g4;
    private OrgTeamGroupEntity gY;

    @BeforeEach
    void setUp() {
        orgX = newOrg(true);
        orgY = newOrg(true);
        g1 = newGroup(orgX.getId(), "G1", 10);
        g2 = newGroup(orgX.getId(), "G2", 20);
        g3 = newGroup(orgX.getId(), "G3", 30);
        g4 = newGroup(orgX.getId(), "G4", 40);
        gY = newGroup(orgY.getId(), "Yのグループ", 10);
        activeTeam(orgX.getId(), g1.getId(), "T1");
        activeTeam(orgX.getId(), g2.getId(), "T2");
        activeTeam(orgX.getId(), g3.getId(), "T3");
        activeTeam(orgX.getId(), g4.getId(), "T4");
        activeTeam(orgX.getId(), null, "T0");
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XD1, orgX.getId(), "DEPUTY_ADMIN");
        seedOrgPerson(XD2, orgX.getId(), "DEPUTY_ADMIN");
        seedOrgPerson(XM, orgX.getId(), "MEMBER");
        seedOrgPerson(XO, orgX.getId(), "MEMBER");
        seedOrgPerson(YA, orgY.getId(), "ADMIN");
        grantManageContent(XD1, orgX.getId());
        flushAndClear();
    }

    // ───────── ヘルパ ─────────

    /** DEPUTY_ADMIN に、権限グループ経由で MANAGE_CONTENT を付ける（XD2 には付けない）。 */
    private void grantManageContent(Long userId, Long orgId) {
        em.createNativeQuery("INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "VALUES ('MANAGE_CONTENT', 'コンテンツ管理', 'ORGANIZATION', NOW(6), NOW(6))")
                .executeUpdate();
        String name = "6B権限束" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        em.createNativeQuery("INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) VALUES (NULL, :oid, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("oid", orgId).setParameter("name", name).executeUpdate();
        Number gid = (Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", name).getSingleResult();
        em.createNativeQuery("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = 'MANAGE_CONTENT'")
                .setParameter("gid", gid.longValue()).executeUpdate();
        em.createNativeQuery("INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId).setParameter("gid", gid.longValue()).executeUpdate();
    }

    private Map<String, Object> templateBody(String name, Map<String, Object> groupItems) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("targetRole", "MEMBERS_AND_ABOVE");
        m.put("preferredChannel", "BULLETIN_THREAD");
        m.put("isDefault", false);
        m.putAll(groupItems);
        return m;
    }

    private ResultActions createTemplate(Long actor, Long orgId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(ORG_TEMPLATES, orgId).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions updateTemplate(Long actor, Long orgId, long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(ORG_TEMPLATE, orgId, id).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listTemplates(Long actor, Long orgId) throws Exception {
        return mockMvc.perform(get(ORG_TEMPLATES, orgId).with(user(actor.toString())));
    }

    /** テンプレートを XA で保存して、その ID を返す（保存自体が 201 であることも確かめる）。 */
    private long saveTemplate(String name, Map<String, Object> groupItems) throws Exception {
        ResultActions r = createTemplate(XA, orgX.getId(), templateBody(name, groupItems));
        r.andExpect(status().isCreated());
        JsonNode root = objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    /** templateId だけを指定した掲示板告知（宛先項目は一切送らない）。 */
    private String templateOnlyBody(long templateId) throws Exception {
        return bulletinBody(Map.of("templateId", templateId));
    }

    // ═════════════════════════════════════════════════════════════
    // AC-G123 保存・取得・認可・スコープ分離
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-G123 グループ3項目の保存と取得")
    class SaveAndFetch {

        @Test
        @DisplayName("XA が個別グループ＋未分類を保存すると、応答と一覧の両方で3項目が返る（POST→GET）")
        void adminSavesIndividualGroups_roundTrip() throws Exception {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("targetGroupIds", ids(g1.getId(), g3.getId()));
            items.put("includeUnassigned", true);
            createTemplate(XA, orgX.getId(), templateBody("個別", items))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.targetGroupIds[0]").value(g1.getId().toString()))
                    .andExpect(jsonPath("$.data.targetGroupIds[1]").value(g3.getId().toString()))
                    .andExpect(jsonPath("$.data.includeUnassigned").value(true));
            flushAndClear();

            listTemplates(XA, orgX.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].targetGroupIds[0]").value(g1.getId().toString()))
                    .andExpect(jsonPath("$.data[0].targetGroupIds[1]").value(g3.getId().toString()))
                    .andExpect(jsonPath("$.data[0].includeUnassigned").value(true));
        }

        @Test
        @DisplayName("範囲「G2 以前」（fromGroupId=null）を保存すると、端の null も含めて往復できる")
        void adminSavesRange_roundTrip() throws Exception {
            createTemplate(XA, orgX.getId(), templateBody("範囲", Map.of("targetGroupRange", range(null, g2.getId()))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.targetGroupRange.toGroupId").value(g2.getId().toString()))
                    .andExpect(jsonPath("$.data.targetGroupRange.fromGroupId").doesNotExist());
            flushAndClear();

            listTemplates(XA, orgX.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].targetGroupRange.toGroupId").value(g2.getId().toString()))
                    .andExpect(jsonPath("$.data[0].includeUnassigned").value(false));
        }

        @Test
        @DisplayName("グループ項目を送らないテンプレートは従来どおり保存でき、3項目は空・false で返る（既存挙動を壊さない）")
        void templateWithoutGroupItems_stillWorks() throws Exception {
            createTemplate(XA, orgX.getId(), templateBody("従来", Map.of()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("従来"))
                    .andExpect(jsonPath("$.data.targetGroupIds").doesNotExist())
                    .andExpect(jsonPath("$.data.targetGroupRange").doesNotExist())
                    .andExpect(jsonPath("$.data.includeUnassigned").value(false));
        }

        @Test
        @DisplayName("PUT で3項目を更新でき、更新後の GET に反映される")
        void update_roundTrip() throws Exception {
            long id = saveTemplate("更新前", Map.of("targetGroupIds", ids(g1.getId())));
            flushAndClear();

            Map<String, Object> items = new LinkedHashMap<>();
            items.put("targetGroupRange", range(g2.getId(), g4.getId()));
            items.put("includeUnassigned", true);
            updateTemplate(XA, orgX.getId(), id, templateBody("更新後", items))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targetGroupRange.fromGroupId").value(g2.getId().toString()))
                    .andExpect(jsonPath("$.data.targetGroupRange.toGroupId").value(g4.getId().toString()))
                    .andExpect(jsonPath("$.data.includeUnassigned").value(true));
            flushAndClear();

            listTemplates(XA, orgX.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].targetGroupRange.fromGroupId").value(g2.getId().toString()))
                    .andExpect(jsonPath("$.data[0].targetGroupIds").doesNotExist());
        }
    }

    @Nested
    @DisplayName("AC-G123 保存できる人・参照できる範囲")
    class Authorization {

        private Map<String, Object> groupItems() {
            return Map.of("targetGroupIds", ids(g1.getId()));
        }

        @Test
        @DisplayName("MANAGE_CONTENT を持つ DEPUTY_ADMIN（XD1）はグループ項目つきで保存できる")
        void deputyWithManageContent_canSave() throws Exception {
            createTemplate(XD1, orgX.getId(), templateBody("XD1", groupItems()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.targetGroupIds[0]").value(g1.getId().toString()));
        }

        @Test
        @DisplayName("MANAGE_CONTENT を持たない DEPUTY_ADMIN（XD2）・MEMBER（XM）は 403 で、何も保存されない")
        void deputyWithoutPermissionAndMember_forbidden() throws Exception {
            createTemplate(XD2, orgX.getId(), templateBody("XD2", groupItems()))
                    .andExpect(status().isForbidden());
            createTemplate(XM, orgX.getId(), templateBody("XM", groupItems()))
                    .andExpect(status().isForbidden());
            flushAndClear();
            listTemplates(XA, orgX.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("MANAGE_CONTENT を持たない DEPUTY_ADMIN（XD2）は PUT でもグループ項目を書き換えられない")
        void deputyWithoutPermission_cannotUpdate() throws Exception {
            long id = saveTemplate("既存", groupItems());
            flushAndClear();
            updateTemplate(XD2, orgX.getId(), id, templateBody("改ざん", Map.of("targetGroupIds", ids(g4.getId()))))
                    .andExpect(status().isForbidden());
            flushAndClear();
            listTemplates(XA, orgX.getId())
                    .andExpect(jsonPath("$.data[0].targetGroupIds[0]").value(g1.getId().toString()));
        }

        @Test
        @DisplayName("他組織 Y の ADMIN（YA）は X のテンプレートを一覧できず（403）、Y の一覧に X のテンプレートは出ない")
        void otherScope_cannotSee() throws Exception {
            saveTemplate("Xの秘密", groupItems());
            flushAndClear();
            listTemplates(YA, orgX.getId()).andExpect(status().isForbidden());
            listTemplates(YA, orgY.getId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("他組織 Y で X のテンプレート ID を使っても 400 BROADCAST_003（存在を区別しない）")
        void otherScopeTemplateId_is003() throws Exception {
            long id = saveTemplate("Xのテンプレート", groupItems());
            flushAndClear();
            long before = feedCount(orgY.getId());
            broadcastToOrg(YA, orgY.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_003"));
            assertThat(feedCount(orgY.getId())).isEqualTo(before);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AC-J01〜J03 テンプレートを使うときのサーバー側解決（§8.6）
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-J01 範囲は送信時の並びで展開される")
    class RangeExpandedAtSendTime {

        @Test
        @DisplayName("「G2 以前」を保存後、G5 を作って G1 の前へ置くと、templateId だけの送信で target_group_ids は G5・G1・G2")
        void rangeExpandsInCurrentOrder() throws Exception {
            long id = saveTemplate("G2以前", Map.of("targetGroupRange", range(null, g2.getId())));
            OrgTeamGroupEntity g5 = newGroup(orgX.getId(), "G5", 5); // G1(10) より前
            activeTeam(orgX.getId(), g5.getId(), "T5");
            flushAndClear();

            ResultActions result = broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id));
            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.warnings", hasSize(0)));
            long feedId = feedIdOf(result);
            assertThat(savedTargetGroupIds(feedId))
                    .containsExactly(g5.getId().toString(), g1.getId().toString(), g2.getId().toString());
            assertThat(snapshotPairs(feedId)).hasSize(3); // T5・T1・T2 のスナップショット
        }

        @Test
        @DisplayName("宛先プレビューも同じ展開で、グループは G5・G1・G2 の並び")
        void previewExpandsInCurrentOrder() throws Exception {
            long id = saveTemplate("G2以前", Map.of("targetGroupRange", range(null, g2.getId())));
            OrgTeamGroupEntity g5 = newGroup(orgX.getId(), "G5", 5);
            activeTeam(orgX.getId(), g5.getId(), "T5");
            flushAndClear();

            preview(XA, orgX.getId(), templateOnlyBody(id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.groups[0].name").value("G5"))
                    .andExpect(jsonPath("$.data.groups[1].name").value("G1"))
                    .andExpect(jsonPath("$.data.groups[2].name").value("G2"))
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(3));
        }

        @Test
        @DisplayName("個別グループ＋範囲＋未分類は和集合で展開される（重複グループは1回）")
        void unionOfIndividualRangeAndUnassigned() throws Exception {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("targetGroupIds", ids(g2.getId(), g4.getId()));
            items.put("targetGroupRange", range(g1.getId(), g2.getId()));
            items.put("includeUnassigned", true);
            long id = saveTemplate("和集合", items);
            flushAndClear();

            ResultActions result = broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id));
            result.andExpect(status().isCreated());
            long feedId = feedIdOf(result);
            assertThat(savedTargetGroupIds(feedId))
                    .containsExactly(g1.getId().toString(), g2.getId().toString(), g4.getId().toString());
            assertThat(savedIncludeUnassigned(feedId)).isTrue();
        }

        @Test
        @DisplayName("リクエストで宛先項目を明示したら、テンプレートより明示が優先される（§8.6 手順2）")
        void explicitItemsOverrideTemplate() throws Exception {
            long id = saveTemplate("G2以前", Map.of("targetGroupRange", range(null, g2.getId())));
            flushAndClear();

            ResultActions result = broadcastToOrg(XA, orgX.getId(),
                    bulletinBody(Map.of("templateId", id, "targetGroupIds", ids(g3.getId()))));
            result.andExpect(status().isCreated());
            assertThat(savedTargetGroupIds(feedIdOf(result))).containsExactly(g3.getId().toString());
        }
    }

    @Nested
    @DisplayName("AC-J02 削除されたグループは除外され warnings に TEMPLATE_GROUPS_REMOVED")
    class DeletedGroupsExcluded {

        @Test
        @DisplayName("個別選択の G2 を削除してから送ると、G2 を除いて成功し、warnings に TEMPLATE_GROUPS_REMOVED")
        void deletedIndividualGroup_excludedWithWarning() throws Exception {
            long id = saveTemplate("G1とG2", Map.of("targetGroupIds", ids(g1.getId(), g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            ResultActions result = broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id));
            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.warnings", hasItem(containsString("TEMPLATE_GROUPS_REMOVED"))));
            assertThat(savedTargetGroupIds(feedIdOf(result))).containsExactly(g1.getId().toString());
        }

        @Test
        @DisplayName("宛先プレビューも同じ警告を返し、グループは G1 だけ")
        void previewShowsWarning() throws Exception {
            long id = saveTemplate("G1とG2", Map.of("targetGroupIds", ids(g1.getId(), g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            preview(XA, orgX.getId(), templateOnlyBody(id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.warnings", hasItem(containsString("TEMPLATE_GROUPS_REMOVED"))))
                    .andExpect(jsonPath("$.data.groups", hasSize(1)))
                    .andExpect(jsonPath("$.data.groups[0].name").value("G1"));
        }

        @Test
        @DisplayName("警告には除外件数が載る（2グループ削除なら \"2\" を含む）")
        void warningCarriesRemovedCount() throws Exception {
            long id = saveTemplate("G1〜G3", Map.of("targetGroupIds", ids(g1.getId(), g2.getId(), g3.getId())));
            softDeleteGroup(g2.getId());
            softDeleteGroup(g3.getId());
            flushAndClear();

            preview(XA, orgX.getId(), templateOnlyBody(id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.warnings", hasItem(containsString("2"))));
        }

        @Test
        @DisplayName("除外した後が空でも、直属メンバーがいる組織なら送信は成功し、宛先は直属メンバーだけ（全チーム宛てにならない）")
        void everythingRemoved_withDirectMembers_isAcceptedAsDirectMembersOnly() throws Exception {
            long id = saveTemplate("G2だけ", Map.of("targetGroupIds", ids(g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            ResultActions result = broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id));
            result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.warnings", hasItem("TEMPLATE_GROUPS_REMOVED:1")));
            long feedId = feedIdOf(result);
            // 絞り込みの記録が残り（全チーム宛て＝記録なしに化けない）、チーム宛先もスナップショットも空
            assertThat(savedTargetTeamIds(feedId)).isNull();
            assertThat(savedTargetGroupIds(feedId)).isEmpty();
            assertThat(savedTargetAudience(feedId)).isNotNull();
            assertThat(snapshotPairs(feedId)).isEmpty();
        }

        @Test
        @DisplayName("除外した後が空で、直属メンバーもいない組織なら 400 BROADCAST_009、フィードは作られない（設計書 §8.3・§8.6 手順5）")
        void everythingRemoved_withoutDirectMembers_is009() throws Exception {
            OrganizationEntity orgW = newOrg(true);
            OrgTeamGroupEntity gw = newGroup(orgW.getId(), "Wのグループ", 0);
            Long wa = 940601098L;
            seedOrgPerson(wa, orgW.getId(), "ADMIN");
            flushAndClear();
            ResultActions saved = createTemplate(wa, orgW.getId(),
                    templateBody("Wのグループだけ", Map.of("targetGroupIds", ids(gw.getId()))));
            saved.andExpect(status().isCreated());
            long id = objectMapper.readTree(saved.andReturn().getResponse().getContentAsString())
                    .path("data").path("id").asLong();
            softDeleteGroup(gw.getId());
            flushAndClear();

            long before = feedCount(orgW.getId());
            broadcastToOrg(wa, orgW.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_009"));
            assertThat(feedCount(orgW.getId())).isEqualTo(before);
        }
        @Test
        @DisplayName("全グループが削除済みのテンプレートでも、プレビューは 200・チーム0件・警告 TEMPLATE_GROUPS_REMOVED:1（400 にしない）")
        void everythingRemoved_previewIs200WithZeroAndWarning() throws Exception {
            long id = saveTemplate("G2だけ", Map.of("targetGroupIds", ids(g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            preview(XA, orgX.getId(), templateOnlyBody(id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedTeamCount").value(0))
                    .andExpect(jsonPath("$.data.groups", hasSize(0)))
                    .andExpect(jsonPath("$.data.warnings", hasItem("TEMPLATE_GROUPS_REMOVED:1")));
        }
    }

    @Nested
    @DisplayName("AC-J03範囲の端が削除されていたら 400 BROADCAST_013")
    class RangeEndDeleted {

        @Test
        @DisplayName("範囲の終了端 G2 を削除してから templateId だけで送ると 400 BROADCAST_013、フィードは作られない")
        void deletedToEnd_is013() throws Exception {
            long id = saveTemplate("G1からG2", Map.of("targetGroupRange", range(g1.getId(), g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            long before = feedCount(orgX.getId());
            broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_013"));
            assertThat(feedCount(orgX.getId())).isEqualTo(before);
        }

        @Test
        @DisplayName("範囲の開始端 G1 を削除しても 400 BROADCAST_013")
        void deletedFromEnd_is013() throws Exception {
            long id = saveTemplate("G1からG3", Map.of("targetGroupRange", range(g1.getId(), g3.getId())));
            softDeleteGroup(g1.getId());
            flushAndClear();

            broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_013"));
        }

        @Test
        @DisplayName("「G2 以前」（開始端なし）の G2 を削除しても 400 BROADCAST_013（片端だけの範囲も同じ）")
        void deletedOpenRangeEnd_is013() throws Exception {
            long id = saveTemplate("G2以前", Map.of("targetGroupRange", range(null, g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_013"));
        }

        @Test
        @DisplayName("宛先プレビューも 400 BROADCAST_013（画面が範囲を空にして選び直させるための合図）")
        void preview_is013() throws Exception {
            long id = saveTemplate("G1からG2", Map.of("targetGroupRange", range(g1.getId(), g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            preview(XA, orgX.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_013"));
        }

        @Test
        @DisplayName("個別選択の削除（警告で続行）と範囲の端の削除（400）が同居したら、範囲の端が優先して 400 BROADCAST_013")
        void rangeEndBeatsIndividualWarning() throws Exception {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("targetGroupIds", ids(g4.getId(), g3.getId()));
            items.put("targetGroupRange", range(g1.getId(), g2.getId()));
            long id = saveTemplate("両方", items);
            softDeleteGroup(g3.getId());
            softDeleteGroup(g2.getId());
            flushAndClear();

            broadcastToOrg(XA, orgX.getId(), templateOnlyBody(id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BROADCAST_013"));
        }

        @Test
        @DisplayName("明示の宛先項目を送っていれば、壊れたテンプレートの範囲には影響されず成功する（§8.6 手順2）")
        void explicitItemsAvoid013() throws Exception {
            long id = saveTemplate("G1からG2", Map.of("targetGroupRange", range(g1.getId(), g2.getId())));
            softDeleteGroup(g2.getId());
            flushAndClear();

            broadcastToOrg(XA, orgX.getId(), bulletinBody(Map.of("templateId", id, "targetGroupIds", ids(g3.getId()))))
                    .andExpect(status().isCreated());
        }
    }
}
