package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 Wave3 機能⑤（加入前相性表示）・機能⑥（村人ミニプロフィール所属村一覧）
 * API契約テスト（<b>試練 / red 先行</b>。実装は後続部隊が green 化する）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>test-first の red テスト</strong>である。対象コントローラ
 * （{@code VillageAffinityController} / プロフィール公開トグル系）は <b>まだ存在しない</b>ため、
 * 本テストは実装クラスを一切 import せず、設計書のURL文字列を MockMvc で直接叩く。
 * 現時点では全エンドポイントが未マップで <b>404</b> を返すので、200/403/429 を期待する
 * ケースはすべて赤になる（それが試練の正しい初期状態）。出陣部隊が実装すると green 化する。</p>
 *
 * <h2>金型</h2>
 * <p>{@code WebhookAuthzContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実MySQL
 * + 手動 SecurityContext）。Spring Security フィルタは無効化するが、認可の HTTP ステータスは
 * アプリ層（{@code BusinessException} → GlobalExceptionHandler）で決まるためフィルタ無効でも検証できる。
 * 未認証は現在の principal 不在（→ 実装後 401）、越境/非同居は 403、存在秘匿は 404 で表す。</p>
 *
 * <h2>正本</h2>
 * <p>設計書 {@code docs/features/F17.2_village_events_activation.md} §8（相性）・§9（所属村一覧）・
 * §11.5 / §11.6（AC本文）。AC↔テストの対応は各 {@code @DisplayName} 冒頭に AC 番号を記す。</p>
 *
 * <h2>フィクスチャの注意</h2>
 * <ul>
 *   <li>村・membership は使い捨て新規作成（seed 汚染回避）。{@code village_memberships} は
 *       {@code subject_type}+{@code subject_id} 構造で {@code user_id} 列は無い（原則1）。</li>
 *   <li>{@code subject_id} は FK を張らないため、users 行を作らず任意の Long を viewer/villager に使える。</li>
 *   <li>「相性の重なり人数」の厳密な算出仕様は §8.4 で確定していない実装詳細のため、本試練では
 *       「対象村の村人のうち、閲覧者ともう1つの共通村に同居している人数」を重なりの代理としてシードする。
 *       出陣部隊がアルゴリズム確定時にフィクスチャを整合させる前提（赤→緑の緑化時に微調整可）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 W3 相性・所属村一覧 API契約テスト（試練 / red）")
class VillageAffinityProfileContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    /** users 行は作らないため衝突しない一意な擬似 userId を採番する。 */
    private static final AtomicLong USER_SEQ = new AtomicLong(17_020_000L);

    private static final String AFFINITY = "/api/v1/villages/{villageId}/affinity/me";
    private static final String PROFILE_TOGGLE =
            "/api/v1/villages/{villageId}/memberships/me/profile-visibility";
    private static final String USER_VILLAGES = "/api/v1/users/{userId}/villages";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能⑤ 加入前相性表示（§8・§11.5）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("機能⑤ GET /villages/{id}/affinity/me — 加入前相性表示")
    class AffinityApi {

        @Test
        @DisplayName("AC-20: 非メンバー＋PUBLIC村 → 200 で §8.3 の5フィールドが返る")
        void ac20_nonMemberPublic_returnsFiveFields() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            // 村人を数名（viewer は非メンバーのまま）
            addMembers(v.getId(), 4);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.categoryMatch").exists())
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").exists())
                    .andExpect(jsonPath("$.data.reasonKeys").isArray())
                    .andExpect(jsonPath("$.data.pioneerAppeal").exists())
                    .andExpect(jsonPath("$.data.memberCount").exists());
        }

        @Test
        @DisplayName("AC-21: 重なり2人 → sharedVillagerBucket=HIDDEN（人数・identity フィールド不在）")
        void ac21_overlapTwo_hidden() throws Exception {
            Long viewer = nextUser();
            VillageEntity target = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            seedOverlap(target, viewer, 2);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, target.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").value("HIDDEN"))
                    // ★差分攻撃対策: 正確な人数・重なった村人の identity は一切返さない（§8.4）
                    .andExpect(jsonPath("$.data.sharedVillagerCount").doesNotExist())
                    .andExpect(jsonPath("$.data.sharedVillagers").doesNotExist())
                    .andExpect(jsonPath("$.data.villagerIds").doesNotExist())
                    .andExpect(jsonPath("$.data.nicknames").doesNotExist());
        }

        @Test
        @DisplayName("AC-21: 重なり3人 → sharedVillagerBucket=FEW（境界 3）")
        void ac21_overlapThree_few() throws Exception {
            Long viewer = nextUser();
            VillageEntity target = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            seedOverlap(target, viewer, 3);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, target.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").value("FEW"))
                    .andExpect(jsonPath("$.data.sharedVillagerCount").doesNotExist());
        }

        @Test
        @DisplayName("AC-21: 重なり10人 → sharedVillagerBucket=MANY（境界 10）")
        void ac21_overlapTen_many() throws Exception {
            Long viewer = nextUser();
            VillageEntity target = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            seedOverlap(target, viewer, 10);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, target.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").value("MANY"))
                    .andExpect(jsonPath("$.data.sharedVillagerCount").doesNotExist());
        }

        @Test
        @DisplayName("AC-21b: レート制限超過で 429（実装前は 404 で赤）")
        void ac21b_rateLimitExceeded_429() throws Exception {
            // NOTE(緑化部隊): 本ケースの green 化には (1) VillageAffinityRateLimitFilter の新設
            // （既定30回/分・キー=userId+villageId・§8.4緩和2）と (2) テストコンテキストで機能する
            // ValkeyRateLimiter が必要（本基底は StringRedisTemplate をモック化しているため、
            // 実カウントには fake/embedded Valkey への差し替えを要する）。
            // また監査 AuditEventType.VILLAGE_AFFINITY_QUERIED 記録（§8.4緩和3）は enum 定数が
            // 未定義のため本試練では compile 断裂回避のため assert しない（緑化部隊が追加）。
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            authenticateAs(viewer);

            int last = 200;
            // 既定 30回/分 を超える回数を叩き、いずれかで 429 になることを期待する
            for (int i = 0; i < 40; i++) {
                last = mockMvc.perform(get(AFFINITY, v.getId()))
                        .andReturn().getResponse().getStatus();
                if (last == 429) {
                    break;
                }
            }
            org.assertj.core.api.Assertions.assertThat(last)
                    .as("相性APIはレート制限（既定30回/分）で 429 を返すべき")
                    .isEqualTo(429);
        }

        @Test
        @DisplayName("AC-22: UNLISTED村を非メンバーが叩くと 404 で存在秘匿（架空村IDと同一の応答＝識別可能なコードを漏らさない）")
        void ac22_unlistedNonMember_404Hidden() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.UNLISTED, VillageJoinPolicy.FREE);

            authenticateAs(viewer);
            // 存在秘匿の契約: UNLISTED 村への相性クエリは「そもそも存在しない村」への応答と
            // 見分けがつかない 404 でなければならない（AFFINITY_NOT_PUBLIC_VILLAGE 等の
            // 「存在するが非公開」を示唆するコードを漏らさない・§8.7）。
            // 架空の villageId への応答コードを基準に、UNLISTED も同一コードであることを照合する。
            // NOTE: これはガードテスト。ルート未実装の現在は両者とも同じ NoHandler 404 で通り得るが、
            //       実装後に UNLISTED だけ別コードを返すと赤くなり、存在秘匿の破れを検知する。
            String ghostCode = errorCode(
                    mockMvc.perform(get(AFFINITY, UUID.randomUUID()))
                            .andExpect(status().isNotFound()));
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(ghostCode));
        }

        @Test
        @DisplayName("AC-22: 未ログインは 401")
        void ac22_unauthenticated_401() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            // 認証なし
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-22: PUBLIC×APPROVAL（承認制）村でも非メンバーに相性を返す（join_policy 不問）")
        void ac22_publicApproval_returns200() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.APPROVAL);
            addMembers(v.getId(), 4);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").exists());
        }

        @Test
        @DisplayName("AC-24a: 総メンバー10人以下のPUBLIC村を未参加者が叩くと pioneerAppeal=true＋memberCount")
        void ac24a_smallVillage_pioneerTrue() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            addMembers(v.getId(), 8); // 総メンバー 8（<=10）

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pioneerAppeal").value(true))
                    .andExpect(jsonPath("$.data.memberCount").value(8));
        }

        @Test
        @DisplayName("AC-24b: 総メンバー11人以上の村では pioneerAppeal=false")
        void ac24b_largeVillage_pioneerFalse() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            addMembers(v.getId(), 11); // 総メンバー 11（>10）

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pioneerAppeal").value(false));
        }

        @Test
        @DisplayName("AC-24c: 参加済みユーザーが小規模村を開いても pioneerAppeal=false（未参加者向けの誘い文句）")
        void ac24c_memberViewer_pioneerFalse() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            addMembers(v.getId(), 5);
            // viewer 自身をメンバーに加える（参加済み）
            persistMember(v.getId(), viewer, VillageRole.VILLAGER);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pioneerAppeal").value(false));
        }

        @Test
        @DisplayName("AC-24d: 小規模村でも重なり2人以下なら sharedVillagerBucket=HIDDEN を維持")
        void ac24d_smallVillage_bucketStillHidden() throws Exception {
            Long viewer = nextUser();
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            seedOverlap(v, viewer, 2); // 重なり2人（小規模村でもバケット閾値は緩めない）

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pioneerAppeal").value(true))
                    .andExpect(jsonPath("$.data.sharedVillagerBucket").value("HIDDEN"));
        }

        @Test
        @DisplayName("AC-24e: 全条件不成立 → reasonKeys=[]・HIDDEN時 reason.sharedVillagers 不付与")
        void ac24e_noReason_emptyArray() throws Exception {
            Long viewer = nextUser();
            // categoryMatch=false・bucket=HIDDEN・pioneerAppeal=false を狙う:
            // カテゴリの異なる11人規模の村で、重なりゼロ
            VillageEntity v = persistVillageWithCategory(
                    VillageVisibility.PUBLIC, VillageJoinPolicy.FREE, "no-match-category");
            addMembers(v.getId(), 11);

            authenticateAs(viewer);
            mockMvc.perform(get(AFFINITY, v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reasonKeys").isArray())
                    .andExpect(jsonPath("$.data.reasonKeys").isEmpty())
                    // HIDDEN のとき「縁のある村人がいます」は差分攻撃の手掛かりになるため付けない（§8.5）
                    .andExpect(jsonPath("$.data.reasonKeys[?(@ == 'village.affinity.reason.sharedVillagers')]")
                            .doesNotExist());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能⑥ 所属村一覧（§9・§11.6）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("機能⑥ profile-visibility 切替 & GET /users/{id}/villages — 所属村一覧")
    class ProfileVillagesApi {

        @Test
        @DisplayName("AC-29: 本人が profile-visibility を切替 → 200・再GETで反映される")
        void ac29_toggleThenReflected() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            Long target = nextUser();
            Long viewer = nextUser();
            persistMember(v.getId(), target, VillageRole.VILLAGER);
            persistMember(v.getId(), viewer, VillageRole.VILLAGER); // 同居者（閲覧の第一関門）

            // (1) 本人が公開 ON へ
            authenticateAs(target);
            mockMvc.perform(patch(PROFILE_TOGGLE, v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"profilePublic\":true}"))
                    .andExpect(status().isOk());

            // (2) 同居者が対象者の所属村一覧を見ると、当該 PUBLIC 村が反映される
            authenticateAs(viewer);
            mockMvc.perform(get(USER_VILLAGES, target))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.villageId == '" + v.getId() + "')]").exists());
        }

        @Test
        @DisplayName("AC-30: 同居＋対象者公開ON∩村PUBLIC のみ返る・村名/村紋/カテゴリ/村ID のみ（ニックネーム/実名不在）")
        void ac30_onlyPublicOnVillages_noNickname() throws Exception {
            Long target = nextUser();
            Long viewer = nextUser();

            // 共通村（同居関係の第一関門）
            VillageEntity common = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMember(common.getId(), target, VillageRole.VILLAGER);
            persistMember(common.getId(), viewer, VillageRole.VILLAGER);

            // 対象者が公開ONにした PUBLIC 村（返るべき）
            VillageEntity shown = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMemberPublic(shown.getId(), target, true);

            // 対象者が公開ONだが UNLISTED の村（村可視性で除外され返らない）
            VillageEntity hiddenByVisibility = persistVillage(VillageVisibility.UNLISTED, VillageJoinPolicy.FREE);
            persistMemberPublic(hiddenByVisibility.getId(), target, true);

            // 対象者が非公開の PUBLIC 村（トグルOFFで除外され返らない）
            VillageEntity hiddenByToggle = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMemberPublic(hiddenByToggle.getId(), target, false);

            authenticateAs(viewer);
            mockMvc.perform(get(USER_VILLAGES, target))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.villageId == '" + shown.getId() + "')]").exists())
                    .andExpect(jsonPath("$.data[?(@.villageId == '" + hiddenByVisibility.getId() + "')]").doesNotExist())
                    .andExpect(jsonPath("$.data[?(@.villageId == '" + hiddenByToggle.getId() + "')]").doesNotExist())
                    // 返る項目: villageId / villageName / villageMonshoUrl / category（§9.3）
                    .andExpect(jsonPath("$.data[0].villageId").exists())
                    .andExpect(jsonPath("$.data[0].villageName").exists())
                    // ★村内 identity（ニックネーム・実名）は横串で晒さない（§9.3・G4）
                    .andExpect(jsonPath("$.data[0].nickname").doesNotExist())
                    .andExpect(jsonPath("$.data[0].displayName").doesNotExist())
                    .andExpect(jsonPath("$.data[0].realName").doesNotExist());
        }

        @Test
        @DisplayName("AC-31: 返せる村0件は、共通村の有無に関わらず一律403（200空配列を返さない）— 共通村あり・公開村0件")
        void ac31_cohabitantButNoPublicVillage_403() throws Exception {
            Long target = nextUser();
            Long viewer = nextUser();

            // 共通村はある（同居関係あり）が、対象者の公開ON×PUBLIC 村は0件
            VillageEntity common = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMember(common.getId(), target, VillageRole.VILLAGER);
            persistMember(common.getId(), viewer, VillageRole.VILLAGER);
            // 対象者は別の村に非公開所属のみ
            VillageEntity priv = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMemberPublic(priv.getId(), target, false);

            authenticateAs(viewer);
            mockMvc.perform(get(USER_VILLAGES, target))
                    // 空配列(200)を返すと「同居関係がある」ことを漏らす → 一律403（§9.4）
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AC-31 IDOR: 非同居者が GET /users/{id}/villages を叩くと403（共通村なしも一律403）")
        void ac31_nonCohabitant_403() throws Exception {
            Long target = nextUser();
            Long stranger = nextUser();

            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            persistMemberPublic(v.getId(), target, true);
            // stranger は target と共通村を一切持たない

            authenticateAs(stranger);
            mockMvc.perform(get(USER_VILLAGES, target))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IDOR: 当該村の非メンバーが profile-visibility を切替えようとすると 403/404（自分の所属でないトグルは不可）")
        void idor_nonMemberToggle_forbiddenOrNotFound() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
            Long nonMember = nextUser();

            authenticateAs(nonMember);
            mockMvc.perform(patch(PROFILE_TOGGLE, v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"profilePublic\":true}"))
                    // 自分が所属しない村の membership 公開状態は操作できない（403 か存在秘匿404）
                    .andExpect(status().is4xxClientError())
                    .andExpect(result -> {
                        int s = result.getResponse().getStatus();
                        org.assertj.core.api.Assertions.assertThat(s)
                                .as("非メンバーのトグルは 403 か 404 であるべき（200/500 は不可）")
                                .isIn(403, 404);
                    });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // フィクスチャヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private static Long nextUser() {
        return USER_SEQ.incrementAndGet();
    }

    /** レスポンスボディの {@code $.error.code} を取り出す（無ければ null）。 */
    private String errorCode(org.springframework.test.web.servlet.ResultActions ra) throws Exception {
        String body = ra.andReturn().getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return null;
        }
        var node = objectMapper.readTree(body).path("error").path("code");
        return node.isMissingNode() ? null : node.asText();
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private VillageEntity persistVillage(VillageVisibility visibility, VillageJoinPolicy joinPolicy) {
        return persistVillageWithCategory(visibility, joinPolicy, "テスト");
    }

    private VillageEntity persistVillageWithCategory(VillageVisibility visibility,
                                                     VillageJoinPolicy joinPolicy,
                                                     String category) {
        long n = System.nanoTime();
        VillageEntity v = VillageEntity.builder()
                .slug("af-" + Long.toHexString(n))
                .name("相性村" + n)
                .description("F17.2 W3 試練用の使い捨て村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(joinPolicy)
                .visibility(visibility)
                .category(category)
                .memberCountCache(0L)
                .createdByUserId(nextUser())
                .build();
        return villageRepository.saveAndFlush(v);
    }

    /** 現役（left_at/banned_at ともに NULL）の村人を1人加える。 */
    private VillageMembershipEntity persistMember(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    /** profile_public を明示指定して村人を加える（機能⑥の公開トグル検証用）。 */
    private VillageMembershipEntity persistMemberPublic(UUID villageId, Long userId, boolean profilePublic) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .profilePublic(profilePublic)
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    /** 対象村に count 人の村人を加える（viewer は含めない）。 */
    private void addMembers(UUID villageId, int count) {
        for (int i = 0; i < count; i++) {
            persistMember(villageId, nextUser(), VillageRole.VILLAGER);
        }
    }

    /**
     * 「相性の重なり」を count 人ぶんシードする。
     *
     * <p>対象村 target に count 人の村人 u1..uN を置き、同じ u1..uN と閲覧者 viewer を
     * もう1つの共通村 W に相互所属させることで「viewer と重なる村人 = count 人」を作る。
     * viewer 自身は target には所属させない（相性は非メンバー視点）。</p>
     */
    private void seedOverlap(VillageEntity target, Long viewer, int count) {
        VillageEntity common = persistVillage(VillageVisibility.PUBLIC, VillageJoinPolicy.FREE);
        persistMember(common.getId(), viewer, VillageRole.VILLAGER);
        for (int i = 0; i < count; i++) {
            Long villager = nextUser();
            persistMember(target.getId(), villager, VillageRole.VILLAGER);
            persistMember(common.getId(), villager, VillageRole.VILLAGER);
        }
    }
}
