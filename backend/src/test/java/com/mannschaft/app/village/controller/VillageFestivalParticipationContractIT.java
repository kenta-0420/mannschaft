package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageFestivalLivePostEntity;
import com.mannschaft.app.village.entity.VillageFestivalRsvpEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageFestivalLivePostRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageFestivalRsvpRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 Wave2 ③お祭りの参加レイヤー — API 契約テスト（試練 / red 先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>red テスト</strong>。骨格のみ（Service/Controller 未実装）の現時点では
 * RSVP・実況の EP が未結線のため、URL 文字列を突きつける本テストは
 * {@code NoResourceFoundException}（404 + {@code COMMON_005}）で必ず赤くなる。
 * 出陣（実装）後に同じ URL へハンドラが結線され、契約どおりの応答で green 化する。</p>
 *
 * <p>金型は姉妹クラス {@link VillageMeetupSecondHalfContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} +
 * {@code @MockitoBean R2StorageService}）と完全同一構成にして TestContext キャッシュを共有する。</p>
 *
 * <p>受け入れ条件（設計書 §5・§11.3）: AC-14・AC-14b・AC-15・AC-16・AC-17c。加えて
 * マスター御下命の IDOR（非メンバー 403／クロス村 404）を明示検証する。</p>
 *
 * <p>祭 RSVP・実況の新エラーコードは設計書 §16.1 で予約された {@code VILLAGE_098}（実況の状態不整合）・
 * {@code VILLAGE_102}（実況の二重タグ）を突きつける（現状未定義ゆえ red）。クロス村は既存
 * {@code VILLAGE_059}（FESTIVAL_NOT_FOUND）で存在秘匿する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave2 ③祭参加 API 契約テスト（試練・red）")
class VillageFestivalParticipationContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageFestivalRepository festivalRepository;

    @Autowired
    private VillageFestivalRsvpRepository rsvpRepository;

    @Autowired
    private VillageFestivalLivePostRepository livePostRepository;

    @Autowired
    private TimelinePostRepository timelinePostRepository;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため宣言する。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_260_001L;
    private static final Long VILLAGER_ID = 17_260_002L;
    private static final Long OTHER_VILLAGER_ID = 17_260_003L;
    private static final Long OUTSIDER_ID = 17_260_004L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-14: 参加表明 upsert（GOING+role_label → 再送 MAYBE で 1 件のまま更新）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-14 参加表明 upsert（PUT .../rsvp）")
    class Rsvp {

        @Test
        @DisplayName("GOING+role_label→200、MAYBE 再送→200 かつ 1 件のまま MAYBE に更新される")
        void upsert_going_then_maybe_updatesInPlace() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.SCHEDULED);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/festivals/{fid}/rsvp", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(rsvpBody("GOING", "出店係"))))
                    .andExpect(status().isOk());

            mockMvc.perform(put("/api/v1/villages/{vid}/festivals/{fid}/rsvp", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(rsvpBody("MAYBE", null))))
                    .andExpect(status().isOk());

            // (festival_id, user_id) UNIQUE により 1 行のまま status が MAYBE へ更新される
            assertThat(rsvpRepository.findByFestivalIdAndUserId(f.getId(), VILLAGER_ID))
                    .isPresent()
                    .get()
                    .satisfies(r -> assertThat(r.getStatus()).isEqualTo(VillageFestivalRsvpStatus.MAYBE));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-14b: RSVP 一覧はページング（size 上限・全件一括で返さない）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-14b RSVP 一覧のページング（GET .../rsvps）")
    class RsvpListPaging {

        @Test
        @DisplayName("size=1 を指定すると 1 件だけ返る（全件一括ではない）")
        void list_isPaged_notReturningAll() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);
            // 2 件の RSVP を仕込む（祭 RSVP は数百人規模になりうるため必ずページングされる・§13.5）
            persistRsvp(f.getId(), VILLAGER_ID, VillageFestivalRsvpStatus.GOING);
            persistRsvp(f.getId(), OTHER_VILLAGER_ID, VillageFestivalRsvpStatus.MAYBE);

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/festivals/{fid}/rsvps", v.getId(), f.getId())
                            .param("page", "0")
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-15: ABSENT は保存できない（400 系）・欠席者一覧 EP は存在しない（404）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-15 ABSENT 拒否・欠席者一覧の不在")
    class NoAbsent {

        @Test
        @DisplayName("RSVP に ABSENT を送ると 400 系（GOING/MAYBE 以外は enum に無い）")
        void absent_rejected_400() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/festivals/{fid}/rsvp", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(rsvpBody("ABSENT", null))))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("欠席者一覧 EP は設計上存在しない（404・欠席を数えないガードレール §10）")
        void absentee_list_endpoint_absent_404() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);

            authAs(VILLAGER_ID);
            // 「欠席者一覧」EP は構造的に作らない。存在すれば設計違反なので 404 を固定する。
            mockMvc.perform(get("/api/v1/villages/{vid}/festivals/{fid}/absentees", v.getId(), f.getId()))
                    .andExpect(status().isNotFound());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-16: 実況タグは ACTIVE 中のみ・二重タグは 409
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-16 実況タグ（POST .../live-posts）")
    class LivePosts {

        @Test
        @DisplayName("SCHEDULED 祭への実況タグは 409 + VILLAGE_098（実況は ACTIVE 中のみ）")
        void scheduled_livePost_rejected_098() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.SCHEDULED);
            TimelinePostEntity post = persistVillagePost(v.getId(), VILLAGER_ID, false);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/festivals/{fid}/live-posts", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("timelinePostId", post.getId()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_098"));
        }

        @Test
        @DisplayName("ENDED 祭への実況タグは 409 + VILLAGE_098")
        void ended_livePost_rejected_098() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ENDED);
            TimelinePostEntity post = persistVillagePost(v.getId(), VILLAGER_ID, false);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/festivals/{fid}/live-posts", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("timelinePostId", post.getId()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_098"));
        }

        @Test
        @DisplayName("同一投稿を同じ祭へ二重タグすると 409 + VILLAGE_102（PK 重複）")
        void duplicate_livePost_conflict_102() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);
            TimelinePostEntity post = persistVillagePost(v.getId(), VILLAGER_ID, false);
            // 既に紐付け済みの状態を作る（複合自然キー festival_id+timeline_post_id）
            persistLivePost(f.getId(), post.getId());

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/festivals/{fid}/live-posts", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("timelinePostId", post.getId()))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_102"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-17c: 実況一覧は timeline 側 deleted_at 済み投稿を除外する
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-17c 実況一覧は削除済み投稿を除外（GET .../live-posts）")
    class LivePostListExcludesDeleted {

        @Test
        @DisplayName("紐付き投稿のうち timeline 側で削除済みのものは実況一覧に出ない")
        void list_excludes_timeline_deleted() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);
            TimelinePostEntity alive = persistVillagePost(v.getId(), VILLAGER_ID, false);
            TimelinePostEntity deleted = persistVillagePost(v.getId(), VILLAGER_ID, true);
            persistLivePost(f.getId(), alive.getId());
            persistLivePost(f.getId(), deleted.getId());

            authAs(VILLAGER_ID);
            // 生存 1 件のみが返るべき（deleted_at 済みは除外・§5.5/§5.6）
            mockMvc.perform(get("/api/v1/villages/{vid}/festivals/{fid}/live-posts", v.getId(), f.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDOR（マスター御下命）: 非メンバー 403 / クロス村 404
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR 村メンバーシップガード（非メンバーは 403）")
    class NonMemberGuard {

        @Test
        @DisplayName("非メンバーの RSVP は 403")
        void outsider_rsvp_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);

            authAs(OUTSIDER_ID); // membership を作らない
            mockMvc.perform(put("/api/v1/villages/{vid}/festivals/{fid}/rsvp", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(rsvpBody("GOING", null))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーの実況タグは 403")
        void outsider_livePost_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE);
            TimelinePostEntity post = persistVillagePost(v.getId(), VILLAGER_ID, false);

            authAs(OUTSIDER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/festivals/{fid}/live-posts", v.getId(), f.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("timelinePostId", post.getId()))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("IDOR クロス村（別村の festivalId を村IDと食い違わせると 404）")
    class CrossVillage {

        @Test
        @DisplayName("村A の URL で村B の祭へ RSVP すると 404 + VILLAGE_059（存在秘匿）")
        void crossVillage_rsvp_404() throws Exception {
            VillageEntity villageA = persistVillage();
            VillageEntity villageB = persistVillage();
            persistMembership(villageA.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity festivalB = persistFestival(villageB.getId(), VillageFestivalStatus.ACTIVE);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/festivals/{fid}/rsvp",
                            villageA.getId(), festivalB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(rsvpBody("GOING", null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_059"));
        }

        @Test
        @DisplayName("村A の URL で村B の祭へ実況タグすると 404 + VILLAGE_059")
        void crossVillage_livePost_404() throws Exception {
            VillageEntity villageA = persistVillage();
            VillageEntity villageB = persistVillage();
            persistMembership(villageA.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageFestivalEntity festivalB = persistFestival(villageB.getId(), VillageFestivalStatus.ACTIVE);
            TimelinePostEntity post = persistVillagePost(villageA.getId(), VILLAGER_ID, false);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/festivals/{fid}/live-posts",
                            villageA.getId(), festivalB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("timelinePostId", post.getId()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_059"));
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

    /** PUT .../rsvp のリクエストボディ（status 必須・roleLabel 任意）。 */
    private Map<String, Object> rsvpBody(String status, String roleLabel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (roleLabel != null) {
            body.put("roleLabel", roleLabel);
        }
        return body;
    }

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("fst-" + Long.toHexString(System.nanoTime()))
                .name("祭参加村" + System.nanoTime())
                .description("祭参加レイヤーテスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
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

    private VillageFestivalEntity persistFestival(UUID villageId, VillageFestivalStatus status) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime starts;
        LocalDateTime ends;
        switch (status) {
            case SCHEDULED -> {
                starts = now.plusDays(7);
                ends = starts.plusDays(2);
            }
            case ACTIVE -> {
                starts = now.minusHours(1);
                ends = now.plusDays(1);
            }
            case ENDED -> {
                starts = now.minusDays(7);
                ends = now.minusDays(5);
            }
            default -> {
                starts = now.plusDays(1);
                ends = now.plusDays(2);
            }
        }
        VillageFestivalEntity f = VillageFestivalEntity.builder()
                .villageId(villageId)
                .title("祭" + System.nanoTime())
                .startsAt(starts)
                .endsAt(ends)
                .status(status)
                .createdByUserId(HEADMAN_ID)
                .build();
        return festivalRepository.saveAndFlush(f);
    }

    private VillageFestivalRsvpEntity persistRsvp(UUID festivalId, Long userId, VillageFestivalRsvpStatus status) {
        VillageFestivalRsvpEntity r = VillageFestivalRsvpEntity.builder()
                .festivalId(festivalId)
                .userId(userId)
                .status(status)
                .build();
        return rsvpRepository.saveAndFlush(r);
    }

    private VillageFestivalLivePostEntity persistLivePost(UUID festivalId, Long timelinePostId) {
        VillageFestivalLivePostEntity lp = VillageFestivalLivePostEntity.builder()
                .festivalId(festivalId)
                .timelinePostId(timelinePostId)
                .build();
        return livePostRepository.saveAndFlush(lp);
    }

    /** VILLAGE スコープの通常タイムライン投稿を作る。{@code deleted} 指定時は論理削除して返す。 */
    private TimelinePostEntity persistVillagePost(UUID villageId, Long userId, boolean deleted) {
        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(PostScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(villageId)
                .userId(userId)
                .content("実況投稿" + System.nanoTime())
                .status(PostStatus.PUBLISHED)
                .build();
        post = timelinePostRepository.saveAndFlush(post);
        if (deleted) {
            post.softDelete();
            post = timelinePostRepository.saveAndFlush(post);
        }
        return post;
    }
}
