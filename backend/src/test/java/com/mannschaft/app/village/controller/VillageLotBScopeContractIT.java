package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 村ドメイン Service 層認可の契約テスト（認可漏れ(IDOR)全域監査 第3波「村」ロットB）。
 *
 * <p>{@code @AuthorizedInService} を付与したエンドポイントについて、村の非メンバー・
 * 権限の無い村人が村内の情報へ到達できないことを固定する。</p>
 *
 * <ul>
 *   <li>{@code VillageNewsletterController#getSettings} — 掲示板と同一の閲覧認可
 *       （{@code MEMBERS_ONLY} 村では非メンバーは 403）</li>
 *   <li>{@code VillageNewsletterController#listSendLogs} — 配信履歴は現役 HEADMAN / ELDER のみ</li>
 *   <li>{@code VillageFeedController#feed} — 村内コンテンツは現役の村人である村に限る</li>
 *   <li>{@code VillageLobbyController#getLobby} / {@code VillageLobbyPresenceController#getPresence} /
 *       {@code VillageSearchController#search} / {@code PostingIdentityController#list} — 非村人は 404</li>
 *   <li>{@code VillageReportController#list} / {@code VillageCalendarController#create} /
 *       {@code VillageRepresentativeController#grant} — 一般村人は 403</li>
 *   <li>{@code VillageCreationRequestController#withdraw} — 他人の申請は 403</li>
 *   <li>{@code VillageController#search} — 結果は PUBLIC の村に限る</li>
 * </ul>
 *
 * <p>他ユーザー・他リソースの ID は高位の値を用い、フィクスチャはすべて JPA リポジトリ経由で作る。
 * 日付は実行時に採り、固定日付を書かない。リクエストボディは常に valid に保つ
 * （ボディ検証は認可判定より前に走るため、400 では認可を検証したことにならない）。</p>
 *
 * <p>金型: {@link VillageCharterContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("村 Service 層認可 契約テスト（第3波 ロットB）")
class VillageLotBScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageMembershipRepository membershipRepository;
    @Autowired private UserVillagePinRepository pinRepository;
    @Autowired private VillageCreationRequestRepository creationRequestRepository;
    @Autowired private TimelinePostRepository timelinePostRepository;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため mock 宣言。 */
    @MockitoBean private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_200_001L;
    private static final Long VILLAGER_ID = 17_200_002L;
    /** 村に一切属さない外部ユーザー（高位の値）。 */
    private static final Long OUTSIDER_ID = 99_172_099L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ニュースレター
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ニュースレター — VillageNewsletterController#getSettings / listSendLogs")
    class Newsletter {

        @Test
        @DisplayName("getSettings: MEMBERS_ONLY 村の非メンバーは 403、村人は 200")
        void getSettings_membersOnlyVillage() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter", v.getId()))
                    .andExpect(status().isForbidden());

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.villageId").value(v.getId().toString()));
        }

        @Test
        @DisplayName("getSettings: 掲示板が PUBLIC の村ならログイン済ユーザーは閲覧できる")
        void getSettings_publicBulletinVillage() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.PUBLIC);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter", v.getId()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listSendLogs: 一般村人は 403、村長は 200")
        void listSendLogs_moderatorOnly() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter/send-logs", v.getId())
                            .param("frequency", "WEEKLY"))
                    .andExpect(status().isForbidden());

            authAs(HEADMAN_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter/send-logs", v.getId())
                            .param("frequency", "WEEKLY"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listSendLogs: 非メンバーは 403")
        void listSendLogs_outsiderForbidden() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/newsletter/send-logs", v.getId())
                            .param("frequency", "MONTHLY"))
                    .andExpect(status().isForbidden());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ダッシュボード村フィード
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("村フィード — VillageFeedController#feed")
    class Feed {

        @Test
        @DisplayName("ピン留めしていても村人でなければ村内の投稿は返らない（ピン一覧には出る）")
        void feed_excludesContentOfVillagesTheCallerIsNotMemberOf() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistTimelinePost(v.getId(), HEADMAN_ID, "村人だけが読める村内の投稿");
            persistPin(OUTSIDER_ID, v.getId());

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/me/village-feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pinnedVillages.length()").value(1))
                    .andExpect(jsonPath("$.data.feed.length()").value(0));
        }

        @Test
        @DisplayName("現役の村人には同じ村の投稿が返る")
        void feed_includesContentForActiveMember() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistTimelinePost(v.getId(), HEADMAN_ID, "村人だけが読める村内の投稿");
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistPin(VILLAGER_ID, v.getId());

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/me/village-feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.feed.length()").value(1))
                    .andExpect(jsonPath("$.data.feed[0].villageId").value(v.getId().toString()));
        }

        @Test
        @DisplayName("BAN された元村人には村内の投稿が返らない")
        void feed_excludesContentForBannedMember() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistTimelinePost(v.getId(), HEADMAN_ID, "村人だけが読める村内の投稿");
            VillageMembershipEntity m = persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            m.setBannedAt(LocalDateTime.now());
            membershipRepository.saveAndFlush(m);
            persistPin(VILLAGER_ID, v.getId());

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/me/village-feed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.feed.length()").value(0));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 非村人の 404（存在秘匿）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("非村人は 404 — VillageLobbyController#getLobby / VillageLobbyPresenceController#getPresence"
            + " / VillageSearchController#search / PostingIdentityController#list")
    class OutsiderHidden {

        @Test
        @DisplayName("VillageLobbyController#getLobby は非村人に 404")
        void getLobby_outsiderNotFound() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/lobby", v.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VillageLobbyPresenceController#getPresence は非村人に 404")
        void getPresence_outsiderNotFound() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/lobby/presence", v.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VillageSearchController#search は非村人に 404")
        void villageInternalSearch_outsiderNotFound() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/search", v.getId()).param("q", "秘密"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PostingIdentityController#list は非村人に 404")
        void postingIdentities_outsiderNotFound() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/me/villages/{vid}/posting-identities", v.getId()))
                    .andExpect(status().isNotFound());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 一般村人は 403（モデレーション権限）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("モデレーション権限 — VillageReportController#list / VillageCalendarController#create"
            + " / VillageRepresentativeController#grant")
    class ModeratorOnly {

        @Test
        @DisplayName("VillageReportController#list は一般村人に 403")
        void listReports_villagerForbidden() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/reports", v.getId()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("VillageCalendarController#create は一般村人に 403（村長は 201）")
        void createCalendarEvent_villagerForbidden() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            // 期日到来で壊れないよう日付は実行時に採る
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "七夕");
            body.put("eventDate", LocalDate.now().toString());
            body.put("isAnnualRecurring", true);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isForbidden());

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("VillageRepresentativeController#grant は一般村人に 403")
        void grantRepresentative_villagerForbidden() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            VillageMembershipEntity teamMembership = persistMembership(
                    v.getId(), 88_170_001L, VillageRole.VILLAGER);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("membershipId", teamMembership.getId().toString());
            body.put("representativeUserId", 88_170_002L);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/representatives", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isForbidden());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 申請の取り下げ・村検索
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("申請取り下げ・村検索")
    class RequestsAndSearch {

        @Test
        @DisplayName("VillageCreationRequestController#withdraw は他人の申請に 403（状態は不変）")
        void withdraw_othersRequestForbidden() throws Exception {
            VillageCreationRequestEntity theirs = persistCreationRequest(99_172_055L);

            authAs(OUTSIDER_ID);
            mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/withdraw", theirs.getId()))
                    .andExpect(status().isForbidden());

            org.assertj.core.api.Assertions
                    .assertThat(creationRequestRepository.findById(theirs.getId()))
                    .get().extracting(VillageCreationRequestEntity::getStatus)
                    .isEqualTo(VillageRequestStatus.PENDING);
        }

        @Test
        @DisplayName("VillageController#search は UNLISTED の村を結果に出さない")
        void search_excludesUnlistedVillages() throws Exception {
            String marker = "検索マーカー" + System.nanoTime();
            persistVillageWithVisibility(VillageVisibility.UNLISTED, marker);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/search").param("q", marker))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private VillageEntity persistVillage(VillageBulletinVisibility bulletinVisibility) {
        VillageEntity v = VillageEntity.builder()
                .slug("lotb-" + Long.toHexString(System.nanoTime()))
                .name("ロットB村" + System.nanoTime())
                .description("ロットB契約テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(bulletinVisibility)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageEntity persistVillageWithVisibility(VillageVisibility visibility, String name) {
        VillageEntity v = VillageEntity.builder()
                .slug("lotb-" + Long.toHexString(System.nanoTime()))
                .name(name)
                .description("ロットB契約テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageMembershipEntity persistMembership(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private UserVillagePinEntity persistPin(Long userId, UUID villageId) {
        UserVillagePinEntity p = UserVillagePinEntity.builder()
                .userId(userId)
                .villageId(villageId)
                .sortOrder(0L)
                .build();
        return pinRepository.saveAndFlush(p);
    }

    private TimelinePostEntity persistTimelinePost(UUID villageId, Long userId, String content) {
        TimelinePostEntity p = TimelinePostEntity.builder()
                .scopeType(PostScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(villageId)
                .userId(userId)
                .content(content)
                .build();
        return timelinePostRepository.saveAndFlush(p);
    }

    private VillageCreationRequestEntity persistCreationRequest(Long requesterUserId) {
        VillageCreationRequestEntity e = VillageCreationRequestEntity.builder()
                .requesterUserId(requesterUserId)
                .proposedName("他人の申請村" + System.nanoTime())
                .proposedSlug("other-" + Long.toHexString(System.nanoTime()))
                .proposedCategory("テスト")
                .purpose("契約テスト用の申請")
                .status(VillageRequestStatus.PENDING)
                .build();
        return creationRequestRepository.saveAndFlush(e);
    }
}
