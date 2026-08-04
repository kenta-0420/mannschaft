package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.repository.VillagePilgrimageRecommendationRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSerendipityScoreRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 村ドメインの自己スコープ エンドポイント契約テスト（認可漏れ(IDOR)全域監査 第3波「村」ロットB）。
 *
 * <p>{@code @SelfScopedEndpoint} を付与した以下のエンドポイントについて、
 * <b>操作・参照の対象が認証主体に束縛され、他ユーザーのデータへ到達できない</b>ことを固定する。</p>
 *
 * <ul>
 *   <li>{@code VillagePinController#listMyPins} / {@code VillagePinController#pin} /
 *       {@code VillagePinController#unpin} / {@code VillagePinController#reorder}</li>
 *   <li>{@code VillageNicknameController#getMyNickname} /
 *       {@code VillageNicknameController#updateMyNickname}</li>
 *   <li>{@code VillagePilgrimageController#getToday} / {@code VillagePilgrimageController#history}</li>
 *   <li>{@code VillageCreationRequestController#listMine}</li>
 *   <li>{@code VillageSerendipityController#getMyScore}</li>
 *   <li>{@code VillageNewsletterController#optOut} / {@code VillageNewsletterController#optIn}</li>
 * </ul>
 *
 * <p>他ユーザーのリソース ID は高位の値を用い、フィクスチャはすべて JPA リポジトリ経由で作る
 * （test プロファイルのスキーマは Entity 由来のため素の INSERT は列名が食い違う）。
 * 日付は実行時に {@code LocalDate.now()} 等から採り、固定日付を書かない。</p>
 *
 * <p>金型: {@link VillageCharterContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} +
 * {@code @Transactional} + {@code @MockitoBean R2StorageService}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("村 自己スコープ EP 契約テスト（第3波 ロットB）")
class VillageSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VillageRepository villageRepository;
    @Autowired private UserVillagePinRepository pinRepository;
    @Autowired private UserVillageNicknameRepository nicknameRepository;
    @Autowired private VillagePilgrimageRecommendationRepository pilgrimageRepository;
    @Autowired private VillageSerendipityScoreRepository serendipityRepository;
    @Autowired private VillageCreationRequestRepository creationRequestRepository;
    @Autowired private VillageNewsletterOptOutRepository optOutRepository;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため mock 宣言。 */
    @MockitoBean private R2StorageService r2StorageService;

    /** 操作者（自分）。 */
    private static final Long ACTOR_ID = 17_100_001L;
    /** 他人（高位の値）。 */
    private static final Long OTHER_ID = 99_170_099L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ピン留め（VillagePinController）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ピン留め — VillagePinController#listMyPins / pin / unpin / reorder")
    class Pins {

        @Test
        @DisplayName("一覧は自分のピンのみを返し、他人のピンは含まない")
        void listMyPins_returnsOnlyOwnPins() throws Exception {
            VillageEntity mine = persistVillage();
            VillageEntity theirs = persistVillage();
            persistPin(ACTOR_ID, mine.getId(), 0L);
            persistPin(OTHER_ID, theirs.getId(), 0L);

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/me/village-pins"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(1))
                    .andExpect(jsonPath("$.data.items[0].villageId").value(mine.getId().toString()));
        }

        @Test
        @DisplayName("ピン留めは自分の行だけを作り、他人のピン集合を変えない")
        void pin_createsRowOwnedByCaller() throws Exception {
            VillageEntity village = persistVillage();

            authAs(ACTOR_ID);
            mockMvc.perform(post("/api/v1/me/village-pins/{villageId}", village.getId()))
                    .andExpect(status().isCreated());

            assertThat(pinRepository.findByUserIdAndVillageId(ACTOR_ID, village.getId())).isPresent();
            assertThat(pinRepository.findByUserIdAndVillageId(OTHER_ID, village.getId())).isEmpty();
        }

        @Test
        @DisplayName("ピン解除は他人の同一村ピンを消さない（自分のピンが無ければ 404）")
        void unpin_doesNotReachOtherUsersPin() throws Exception {
            VillageEntity village = persistVillage();
            persistPin(OTHER_ID, village.getId(), 0L);

            authAs(ACTOR_ID);
            mockMvc.perform(delete("/api/v1/me/village-pins/{villageId}", village.getId()))
                    .andExpect(status().isNotFound());

            assertThat(pinRepository.findByUserIdAndVillageId(OTHER_ID, village.getId())).isPresent();
        }

        @Test
        @DisplayName("並び替えは他人のピンを含む列を受け付けない（自分のピン集合と不一致は 422）")
        void reorder_rejectsOtherUsersVillageIds() throws Exception {
            VillageEntity mine = persistVillage();
            VillageEntity theirs = persistVillage();
            persistPin(ACTOR_ID, mine.getId(), 0L);
            persistPin(OTHER_ID, theirs.getId(), 0L);

            authAs(ACTOR_ID);
            mockMvc.perform(patch("/api/v1/me/village-pins/order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("orderedVillageIds",
                                    List.of(mine.getId().toString(), theirs.getId().toString())))))
                    .andExpect(status().isUnprocessableEntity());

            // 他人のピンの並びは無傷
            assertThat(pinRepository.findByUserIdAndVillageId(OTHER_ID, theirs.getId()))
                    .get().extracting(UserVillagePinEntity::getSortOrder).isEqualTo(0L);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 村ニックネーム（VillageNicknameController）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("村ニックネーム — VillageNicknameController#getMyNickname / updateMyNickname")
    class Nicknames {

        @Test
        @DisplayName("取得は自分の行のみを見る（他人が登録済みでも自分が未登録なら data:null）")
        void getMyNickname_doesNotReachOtherUsersRow() throws Exception {
            persistNickname(OTHER_ID, "他人の村名");

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/me/village-nickname"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("更新は自分の行のみを書き換え、他人の行は不変")
        void updateMyNickname_updatesOnlyOwnRow() throws Exception {
            persistNickname(OTHER_ID, "他人の村名");

            authAs(ACTOR_ID);
            mockMvc.perform(put("/api/v1/me/village-nickname")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("nickname", "自分の村名"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nickname").value("自分の村名"));

            assertThat(nicknameRepository.findByUserIdAndVillageIdIsNull(OTHER_ID))
                    .get().extracting(UserVillageNicknameEntity::getNickname).isEqualTo("他人の村名");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 巡礼（VillagePilgrimageController）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("巡礼 — VillagePilgrimageController#getToday / history")
    class Pilgrimage {

        @Test
        @DisplayName("今日の推薦は自分の行のみを見る（他人の当日推薦は返らない）")
        void getToday_doesNotReachOtherUsersRecommendation() throws Exception {
            VillageEntity village = persistVillage();
            persistRecommendation(OTHER_ID, village.getId(), LocalDate.now());

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/me/pilgrimage/today"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("履歴は自分の推薦のみを返す")
        void history_returnsOnlyOwnRecommendations() throws Exception {
            VillageEntity village = persistVillage();
            persistRecommendation(OTHER_ID, village.getId(), LocalDate.now().minusDays(1));
            persistRecommendation(ACTOR_ID, village.getId(), LocalDate.now().minusDays(2));

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/me/pilgrimage/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("他人の推薦 ID を指定した訪問記録は 404 で存在を秘匿し、visited_at を書かない")
        void recordVisit_otherUsersRecommendation_isNotFound() throws Exception {
            VillageEntity village = persistVillage();
            VillagePilgrimageRecommendationEntity theirs =
                    persistRecommendation(OTHER_ID, village.getId(), LocalDate.now());

            authAs(ACTOR_ID);
            mockMvc.perform(post("/api/v1/me/pilgrimage/{id}/visit", theirs.getId()))
                    .andExpect(status().isNotFound());

            assertThat(pilgrimageRepository.findById(theirs.getId()))
                    .get().extracting(VillagePilgrimageRecommendationEntity::getVisitedAt).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 村作成申請・ご縁スコア・ニュースレター購読
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("申請一覧・ご縁スコア・購読設定")
    class OtherSelfScoped {

        @Test
        @DisplayName("VillageCreationRequestController#listMine は自分の申請のみを返す")
        void listMine_returnsOnlyOwnRequests() throws Exception {
            persistCreationRequest(OTHER_ID, "other-village-slug");
            persistCreationRequest(ACTOR_ID, "my-village-slug");

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/me/village-creation-requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].slug").value("my-village-slug"));
        }

        @Test
        @DisplayName("VillageSerendipityController#getMyScore は他人のスコアに到達しない（自分の行が無ければ 404）")
        void getMyScore_doesNotReachOtherUsersScore() throws Exception {
            VillageEntity village = persistVillage();
            persistScore(village.getId(), OTHER_ID, 999L);

            authAs(ACTOR_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/serendipity-scores/me", village.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("VillageNewsletterController#optOut は自分の購読停止行のみを作る")
        void optOut_createsRowOwnedByCaller() throws Exception {
            VillageEntity village = persistVillage();

            authAs(ACTOR_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/newsletter/opt-out", village.getId()))
                    .andExpect(status().isNoContent());

            assertThat(optOutRepository.existsByVillageIdAndUserId(village.getId(), ACTOR_ID)).isTrue();
            assertThat(optOutRepository.existsByVillageIdAndUserId(village.getId(), OTHER_ID)).isFalse();
        }

        @Test
        @DisplayName("VillageNewsletterController#optIn は他人の購読停止行を消さない（自分の行が無ければ 409）")
        void optIn_doesNotReachOtherUsersOptOut() throws Exception {
            VillageEntity village = persistVillage();
            persistOptOut(village.getId(), OTHER_ID);

            authAs(ACTOR_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/newsletter/opt-out", village.getId()))
                    .andExpect(status().isConflict());

            assertThat(optOutRepository.existsByVillageIdAndUserId(village.getId(), OTHER_ID)).isTrue();
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

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("selfscope-" + Long.toHexString(System.nanoTime()))
                .name("自己スコープ村" + System.nanoTime())
                .description("自己スコープ契約テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(ACTOR_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private UserVillagePinEntity persistPin(Long userId, UUID villageId, Long sortOrder) {
        UserVillagePinEntity p = UserVillagePinEntity.builder()
                .userId(userId)
                .villageId(villageId)
                .sortOrder(sortOrder)
                .build();
        return pinRepository.saveAndFlush(p);
    }

    private UserVillageNicknameEntity persistNickname(Long userId, String nickname) {
        UserVillageNicknameEntity n = new UserVillageNicknameEntity();
        n.setUserId(userId);
        n.setNickname(nickname);
        return nicknameRepository.saveAndFlush(n);
    }

    private VillagePilgrimageRecommendationEntity persistRecommendation(
            Long userId, UUID villageId, LocalDate date) {
        VillagePilgrimageRecommendationEntity r = VillagePilgrimageRecommendationEntity.builder()
                .userId(userId)
                .recommendedVillageId(villageId)
                .recommendedDate(date)
                .reason("契約テスト")
                .build();
        return pilgrimageRepository.saveAndFlush(r);
    }

    private VillageSerendipityScoreEntity persistScore(UUID villageId, Long userId, Long score) {
        VillageSerendipityScoreEntity s = VillageSerendipityScoreEntity.builder()
                .villageId(villageId)
                .userId(userId)
                .encounterCount(1L)
                .interactionScore(score)
                .build();
        return serendipityRepository.saveAndFlush(s);
    }

    private VillageCreationRequestEntity persistCreationRequest(Long requesterUserId, String slug) {
        VillageCreationRequestEntity e = VillageCreationRequestEntity.builder()
                .requesterUserId(requesterUserId)
                .proposedName("申請村" + slug)
                .proposedSlug(slug)
                .proposedCategory("テスト")
                .purpose("契約テスト用の申請")
                .status(VillageRequestStatus.PENDING)
                .build();
        return creationRequestRepository.saveAndFlush(e);
    }

    private VillageNewsletterOptOutEntity persistOptOut(UUID villageId, Long userId) {
        VillageNewsletterOptOutEntity e = VillageNewsletterOptOutEntity.builder()
                .villageId(villageId)
                .userId(userId)
                .build();
        return optOutRepository.saveAndFlush(e);
    }
}
