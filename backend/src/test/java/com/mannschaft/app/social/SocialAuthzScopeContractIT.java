package com.mannschaft.app.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.UserSocialProfileRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.4 / F01.7 Phase 2 ソーシャル（フォロー・プロフィール）ドメインの認可契約テスト。
 *
 * <p>本 IT は「他人のリソースに到達できないこと」を固定する。認可根治戦役 第4波ロットE で
 * 監査済の 16 エンドポイント（{@code FollowController} 5 / {@code SocialProfileController} 6 /
 * {@code UserFollowController} 5）を対象とする。</p>
 *
 * <p>対象エンドポイント（{@code Controller#method} 形式）:</p>
 * <ul>
 *   <li>{@code FollowController#follow / unfollow / getFollowing / getFollowers / isFollowing}
 *       — いずれも自己スコープ（{@code SecurityUtils.getCurrentUserId()} のみを follower 側の
 *       検索・更新キーとして使う）。</li>
 *   <li>{@code SocialProfileController#createProfile / getMyProfile / updateProfile /
 *       deactivateProfile} — 自己スコープ。</li>
 *   <li>{@code SocialProfileController#getProfileByHandle / getProfileByUserId} — 無効化済み
 *       プロフィールは所有者以外に非公開（{@code SocialProfileService} 内の
 *       {@code isActive} 判定）。</li>
 *   <li>{@code UserFollowController#getUserFollowing / getUserFollowers} — 対象ユーザーの
 *       フォロー一覧公開設定（{@code FollowListVisibility}）に従って閲覧を制限する
 *       （{@code FollowService#checkFollowListAccess}）。</li>
 *   <li>{@code UserFollowController#getFollowedTeams / getFollowListVisibility /
 *       updateFollowListVisibility} — 自己スコープ。</li>
 * </ul>
 *
 * <p>金型: {@code ChatAuthzScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F04.4 ソーシャル 認可契約テスト（他人のリソースへ到達しないこと）")
class SocialAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private UserSocialProfileRepository profileRepository;

    @PersistenceContext
    private EntityManager em;

    private Long userAId;
    private Long userBId;
    /** フォロー一覧を PUBLIC のまま持つ対象ユーザー。 */
    private Long publicTargetId;
    /** フォロー一覧を PRIVATE にした対象ユーザー。 */
    private Long privateTargetId;

    private Long userAToPublicFollowId;

    private Long activeProfileUserId;
    private String activeProfileHandle;
    private Long inactiveProfileUserId;
    private String inactiveProfileHandle;

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);

        userAId = insertUser("socialauthz-a-" + uniq + "@example.com", "PUBLIC");
        userBId = insertUser("socialauthz-b-" + uniq + "@example.com", "PUBLIC");
        publicTargetId = insertUser("socialauthz-pub-" + uniq + "@example.com", "PUBLIC");
        privateTargetId = insertUser("socialauthz-priv-" + uniq + "@example.com", "PRIVATE");

        userAToPublicFollowId = followRepository.save(FollowEntity.builder()
                .followerType(FollowerType.USER)
                .followerId(userAId)
                .followedType(FollowerType.USER)
                .followedId(publicTargetId)
                .build()).getId();
        // privateTarget も何かをフォローしている（getUserFollowing の被閲覧データ）。
        followRepository.save(FollowEntity.builder()
                .followerType(FollowerType.USER)
                .followerId(privateTargetId)
                .followedType(FollowerType.USER)
                .followedId(publicTargetId)
                .build());

        activeProfileUserId = insertUser("socialauthz-active-" + uniq + "@example.com", "PUBLIC");
        activeProfileHandle = "socialauthzactive" + uniq;
        profileRepository.save(UserSocialProfileEntity.builder()
                .userId(activeProfileUserId)
                .handle(activeProfileHandle)
                .displayName("有効プロフィール")
                .isActive(true)
                .build());

        inactiveProfileUserId = insertUser("socialauthz-inactive-" + uniq + "@example.com", "PUBLIC");
        inactiveProfileHandle = "socialauthzinactive" + uniq;
        profileRepository.save(UserSocialProfileEntity.builder()
                .userId(inactiveProfileUserId)
                .handle(inactiveProfileHandle)
                .displayName("無効化済みプロフィール")
                .isActive(false)
                .build());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. FollowController#follow（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. FollowController#follow（フォロー作成・自己スコープ）")
    class Follow {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/social/follows")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("followedType", "USER", "followedId", privateTargetId))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身が follower として201で作成される")
        void 認証ユーザー自身がfollowerとして作成される() throws Exception {
            setAuth(userBId);
            mockMvc.perform(post("/api/v1/social/follows")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("followedType", "USER", "followedId", privateTargetId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.followerId").value(userBId.intValue()));

            assertThat(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, userBId, FollowerType.USER, privateTargetId)).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. FollowController#unfollow（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. FollowController#unfollow（アンフォロー・自己スコープ）")
    class Unfollow {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/social/follows")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーが同じ followedId を指定しても、他人のフォロー行は消えない")
        void 他人のフォロー行は消えない() throws Exception {
            setAuth(userBId);
            mockMvc.perform(delete("/api/v1/social/follows")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isBadRequest()); // FOLLOW_NOT_FOUND（userB自身は未フォロー）

            assertThat(followRepository.findById(userAToPublicFollowId)).isPresent();
        }

        @Test
        @DisplayName("正常系: 本人は自分のフォローを204で解除できる")
        void 本人は自分のフォローを解除できる() throws Exception {
            setAuth(userAId);
            mockMvc.perform(delete("/api/v1/social/follows")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isNoContent());

            assertThat(followRepository.findById(userAToPublicFollowId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. FollowController#getFollowing（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. FollowController#getFollowing（フォロー中一覧・自己スコープ）")
    class GetFollowing {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/social/follows/following"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのフォロー中一覧は混入しない")
        void 他人のフォロー中一覧は混入しない() throws Exception {
            setAuth(userBId);
            mockMvc.perform(get("/api/v1/social/follows/following"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(userAToPublicFollowId.intValue()))));
        }

        @Test
        @DisplayName("正常系: 本人には自分のフォロー中一覧が返る")
        void 本人には自分のフォロー中一覧が返る() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/follows/following"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(userAToPublicFollowId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. FollowController#getFollowers（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. FollowController#getFollowers（フォロワー一覧・自己スコープ）")
    class GetFollowers {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/social/follows/followers"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのフォロワー一覧は混入しない")
        void 他人のフォロワー一覧は混入しない() throws Exception {
            setAuth(userBId);
            mockMvc.perform(get("/api/v1/social/follows/followers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(userAToPublicFollowId.intValue()))));
        }

        @Test
        @DisplayName("正常系: 本人には自分のフォロワー一覧が返る")
        void 本人には自分のフォロワー一覧が返る() throws Exception {
            setAuth(publicTargetId);
            mockMvc.perform(get("/api/v1/social/follows/followers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(userAToPublicFollowId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. FollowController#isFollowing（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. FollowController#isFollowing（フォロー状態確認・自己スコープ）")
    class IsFollowing {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/social/follows/check")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 本人のフォロー状態のみを判定する（他人の関係とは独立）")
        void 本人のフォロー状態のみを判定する() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/follows/check")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(true));

            setAuth(userBId);
            mockMvc.perform(get("/api/v1/social/follows/check")
                            .param("followedType", "USER")
                            .param("followedId", publicTargetId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. SocialProfileController#createProfile（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. SocialProfileController#createProfile（プロフィール作成・自己スコープ）")
    class CreateProfile {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/social/profiles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("handle", "shouldnotcreate"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身の userId で201作成される")
        void 認証ユーザー自身のuserIdで作成される() throws Exception {
            setAuth(userAId);
            String handle = "socialauthznew" + Long.toString(System.nanoTime(), 36);
            mockMvc.perform(post("/api/v1/social/profiles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("handle", handle))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.userId").value(userAId.intValue()));

            assertThat(profileRepository.findByUserId(userAId)).isPresent();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. SocialProfileController#getMyProfile（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. SocialProfileController#getMyProfile（自分のプロフィール取得・自己スコープ）")
    class GetMyProfile {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/social/profiles/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 本人には自分のプロフィールが返り、他人のプロフィールは返らない")
        void 本人には自分のプロフィールが返る() throws Exception {
            setAuth(activeProfileUserId);
            mockMvc.perform(get("/api/v1/social/profiles/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(activeProfileUserId.intValue()));

            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. SocialProfileController#updateProfile（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. SocialProfileController#updateProfile（プロフィール更新・自己スコープ）")
    class UpdateProfile {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/social/profiles/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("displayName", "改竄"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 本人のプロフィール行のみが更新され、他人の行は不変")
        void 本人のプロフィール行のみが更新される() throws Exception {
            setAuth(activeProfileUserId);
            mockMvc.perform(patch("/api/v1/social/profiles/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("displayName", "更新後の表示名"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.displayName").value("更新後の表示名"));

            assertThat(profileRepository.findByUserId(inactiveProfileUserId).orElseThrow().getDisplayName())
                    .isEqualTo("無効化済みプロフィール");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. SocialProfileController#getProfileByHandle（無効化済みは非公開）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. SocialProfileController#getProfileByHandle（ハンドル指定取得・無効化済みは非公開）")
    class GetProfileByHandle {

        // 「未認証は401」は本 IT では検証できないため意図的に置いていない: 本クラスは
        // @AutoConfigureMockMvc(addFilters = false) でセキュリティフィルタチェーンを無効化しており、
        // 未認証リクエストがフィルタ（SecurityConfig.java:454 の .anyRequest().authenticated()）で
        // 401 になる経路をこの構成では観測できない。getProfileByHandle は認証ユーザーを
        // 一切参照しない（handle のみで引く）ため、フィルタ無効下では 200 まで素通りしてしまう。
        // 未認証拒否そのものは SecurityConfig の deny-by-default で本番では成立している。

        @Test
        @DisplayName("無効化済みプロフィールはハンドル指定でも404（PROFILE_INACTIVE、"
                + "PROFILE_NOT_FOUND と同一の存在秘匿・ロットDでステータスを404に是正）")
        void 無効化済みプロフィールは非公開() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/handle/{handle}", inactiveProfileHandle))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: 有効なプロフィールはハンドルで200取得できる")
        void 有効なプロフィールは取得できる() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/handle/{handle}", activeProfileHandle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(activeProfileUserId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. SocialProfileController#getProfileByUserId（無効化済みは非公開）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. SocialProfileController#getProfileByUserId（ユーザーID指定取得・無効化済みは非公開）")
    class GetProfileByUserId {

        // 「未認証は401」は本 IT では検証できないため意図的に置いていない（GetProfileByHandle と同一理由）:
        // 本クラスは @AutoConfigureMockMvc(addFilters = false) でセキュリティフィルタチェーンを
        // 無効化しており、未認証リクエストがフィルタ（SecurityConfig.java:454 の
        // .anyRequest().authenticated()）で 401 になる経路をこの構成では観測できない。
        // getProfileByUserId も認証ユーザーを一切参照しない（userId のみで引く）ため、
        // フィルタ無効下では 200 まで素通りしてしまう。未認証拒否そのものは SecurityConfig の
        // deny-by-default で本番では成立している。

        @Test
        @DisplayName("無効化済みプロフィールはユーザーID指定でも404（PROFILE_INACTIVE、"
                + "PROFILE_NOT_FOUND と同一の存在秘匿・迂回できない・ロットDでステータスを404に是正）")
        void 無効化済みプロフィールは非公開() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/users/{userId}", inactiveProfileUserId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: 有効なプロフィールはユーザーIDで200取得できる")
        void 有効なプロフィールは取得できる() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/users/{userId}", activeProfileUserId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(activeProfileUserId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. SocialProfileController#deactivateProfile（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. SocialProfileController#deactivateProfile（プロフィール無効化・自己スコープ）")
    class DeactivateProfile {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/social/profiles/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 本人のプロフィールのみ204で無効化され、他人の行は不変")
        void 本人のプロフィールのみ無効化される() throws Exception {
            setAuth(activeProfileUserId);
            mockMvc.perform(delete("/api/v1/social/profiles/me"))
                    .andExpect(status().isNoContent());

            assertThat(profileRepository.findByUserId(activeProfileUserId).orElseThrow().getIsActive()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. UserFollowController#getUserFollowing（公開設定に従う）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. UserFollowController#getUserFollowing（他ユーザーのフォロー中一覧・公開設定に従う）")
    class GetUserFollowing {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/{userId}/following", publicTargetId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PRIVATE設定の対象ユーザーは本人以外に403（FOLLOW_LIST_NOT_PUBLIC、"
                + "対象ユーザーの存在自体は既知で一覧のみ非公開のため権限不足・ロットDでステータスを403に是正）")
        void PRIVATE設定は本人以外に非公開() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/users/{userId}/following", privateTargetId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: PUBLIC設定の対象ユーザーのフォロー中一覧は誰でも200で取得できる")
        void PUBLIC設定は誰でも取得できる() throws Exception {
            setAuth(userBId);
            mockMvc.perform(get("/api/v1/users/{userId}/following", userAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(userAToPublicFollowId.intValue())));
        }

        @Test
        @DisplayName("正常系: 本人はPRIVATE設定でも自分のフォロー中一覧を取得できる")
        void 本人はPRIVATE設定でも取得できる() throws Exception {
            setAuth(privateTargetId);
            mockMvc.perform(get("/api/v1/users/{userId}/following", privateTargetId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. UserFollowController#getUserFollowers（公開設定に従う）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. UserFollowController#getUserFollowers（他ユーザーのフォロワー一覧・公開設定に従う）")
    class GetUserFollowers {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/{userId}/followers", publicTargetId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PRIVATE設定の対象ユーザーは本人以外に403（FOLLOW_LIST_NOT_PUBLIC、"
                + "対象ユーザーの存在自体は既知で一覧のみ非公開のため権限不足・ロットDでステータスを403に是正）")
        void PRIVATE設定は本人以外に非公開() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/users/{userId}/followers", privateTargetId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: PUBLIC設定の対象ユーザーのフォロワー一覧は誰でも200で取得できる")
        void PUBLIC設定は誰でも取得できる() throws Exception {
            setAuth(userBId);
            mockMvc.perform(get("/api/v1/users/{userId}/followers", publicTargetId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(userAToPublicFollowId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. UserFollowController#getFollowedTeams（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. UserFollowController#getFollowedTeams（フォロー中チーム一覧・自己スコープ）")
    class GetFollowedTeams {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/followed-teams"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身のチームフォロー一覧のみ返る")
        void 認証ユーザー自身のチームフォロー一覧のみ返る() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/users/me/followed-teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. UserFollowController#getFollowListVisibility（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. UserFollowController#getFollowListVisibility（公開設定取得・自己スコープ）")
    class GetFollowListVisibility {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/follow-list-visibility"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身の公開設定が返る")
        void 認証ユーザー自身の公開設定が返る() throws Exception {
            setAuth(privateTargetId);
            mockMvc.perform(get("/api/v1/users/me/follow-list-visibility"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. UserFollowController#updateFollowListVisibility（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. UserFollowController#updateFollowListVisibility（公開設定更新・自己スコープ）")
    class UpdateFollowListVisibility {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(put("/api/v1/users/me/follow-list-visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("visibility", "PRIVATE"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身の設定のみ204で更新され、他人の設定は不変")
        void 認証ユーザー自身の設定のみ更新される() throws Exception {
            setAuth(userAId);
            mockMvc.perform(put("/api/v1/users/me/follow-list-visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("visibility", "PRIVATE"))))
                    .andExpect(status().isNoContent());

            em.flush();
            em.clear();

            assertThat(fetchFollowListVisibility(userAId)).isEqualTo("PRIVATE");
            assertThat(fetchFollowListVisibility(publicTargetId)).isEqualTo("PUBLIC");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. ロットDステータス契約（SOCIAL_001/002/003）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("17. ロットDステータス契約（PROFILE_NOT_FOUND/HANDLE_ALREADY_TAKEN/PROFILE_ALREADY_EXISTS）")
    class LotDStatusContract {

        @Test
        @DisplayName("存在しないハンドルの取得は404（PROFILE_NOT_FOUND）")
        void 存在しないハンドルは404() throws Exception {
            setAuth(userAId);
            mockMvc.perform(get("/api/v1/social/profiles/handle/{handle}", "socialauthz-no-such-handle"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("既にプロフィールを持つユーザーの再作成は409（PROFILE_ALREADY_EXISTS）")
        void 既存ユーザーの再作成は409() throws Exception {
            // activeProfileUserId は setUp で既にプロフィールを持つ。
            setAuth(activeProfileUserId);
            mockMvc.perform(post("/api/v1/social/profiles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("handle", "socialauthz-dup-handle-attempt"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("既に使用中のハンドルでの新規作成は409（HANDLE_ALREADY_TAKEN）")
        void 使用中ハンドルでの新規作成は409() throws Exception {
            // activeProfileHandle は setUp で activeProfileUserId が既に取得済み。
            setAuth(userAId);
            mockMvc.perform(post("/api/v1/social/profiles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("handle", activeProfileHandle))))
                    .andExpect(status().isConflict());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 ChatAuthzScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String fetchFollowListVisibility(Long userId) {
        return (String) em.createNativeQuery(
                        "SELECT follow_list_visibility FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
    }

    private Long insertUser(String email, String followListVisibility) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'SOCIALAUTHZ', 'テスト', 'SOCIALAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, :followListVisibility, "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("followListVisibility", followListVisibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
