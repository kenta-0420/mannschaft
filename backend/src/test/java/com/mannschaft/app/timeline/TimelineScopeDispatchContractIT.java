package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.controller.TimelineFeedController;
import com.mannschaft.app.timeline.controller.TimelinePostController;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 認可根治戦役 Wave6: timeline のスコープ判定を「網羅的ディスパッチ」に揃えたことの契約テスト。
 *
 * <p>本 IT の主眼は <b>{@link PostScopeType} の全 8 値それぞれについて、
 * 越境拒否と正常系の双方を固定する</b>ことにある。{@link TimelineReadScopeContractIT} が
 * TEAM / ORGANIZATION / VILLAGE / PUBLIC / PERSONAL の「読み取り BOLA」を対象にしているのに対し、
 * 本 IT は <b>スコープ種別ディスパッチの網羅性そのもの</b>（＝どの enum 値も無検証で通過しないこと）を
 * 対象とする。</p>
 *
 * <p>金型: {@link TimelineReadScopeContractIT}（Controller 直接 Autowire + SecurityContext 差し替え +
 * JPA リポジトリ直接 save でのシード方式）。</p>
 *
 * <p>敷設後の仕様:</p>
 * <ul>
 *   <li>PUBLIC — 誰でも可（公開スコープ）</li>
 *   <li>TEAM / ORGANIZATION — {@code AccessControlService#checkMembership}（非メンバーは COMMON_002・403）</li>
 *   <li>PERSONAL — {@code scopeId} が呼び出し元本人であること（他人の PERSONAL 指定は COMMON_002・403）</li>
 *   <li>VILLAGE — {@code scopeVillageId} 経由の村メンバー検証が正路。{@code scope_id} 経由の
 *       指定は fail-closed（VILLAGE の {@code scope_id} は常に 0 で全村衝突するため）</li>
 *   <li>FRIEND_TEAM / FRIEND_FORWARD / FRIEND_ARCHIVE — 汎用タイムライン経路では fail-closed。
 *       正路は social ドメイン（MANAGE_FRIEND_TEAMS 権限つき）の friend-feed API</li>
 * </ul>
 */
@DisplayName("timeline スコープ種別の網羅的ディスパッチ契約テスト（認可根治 Wave6）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineScopeDispatchContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TimelineFeedController feedController;

    @Autowired
    private TimelinePostController postController;

    @Autowired
    private TimelinePostRepository postRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository villageMembershipRepository;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    private static final Long USER_TEAM_A_MEMBER = 92_301L;
    private static final Long USER_ORG_A_MEMBER = 92_302L;
    private static final Long USER_OUTSIDER = 92_303L;
    private static final Long USER_VILLAGE_MEMBER = 92_304L;

    // --- 所属スコープ ---
    private static final Long TEAM_A = 70_301L;
    private static final Long ORG_A = 80_301L;

    /** 汎用タイムライン経路では常に fail-closed とする scope 種別。 */
    private static final List<PostScopeType> FRIEND_SCOPES = List.of(
            PostScopeType.FRIEND_TEAM,
            PostScopeType.FRIEND_FORWARD,
            PostScopeType.FRIEND_ARCHIVE);

    private UUID villageId;

    @BeforeEach
    void setUp() {
        membershipRepository.save(membership(USER_TEAM_A_MEMBER, ScopeType.TEAM, TEAM_A));
        membershipRepository.save(membership(USER_ORG_A_MEMBER, ScopeType.ORGANIZATION, ORG_A));

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("w6t-village-" + System.nanoTime())
                .name("Wave6 網羅ディスパッチ検証村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .build());
        villageId = village.getId();
        villageMembershipRepository.save(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_VILLAGE_MEMBER)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private MembershipEntity membership(Long userId, ScopeType scopeType, Long scopeId) {
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private TimelinePostEntity savePost(PostScopeType scopeType, Long scopeId, UUID scopeVillageId, Long userId) {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scopeVillageId(scopeVillageId)
                .userId(userId)
                .content("w6-post-" + scopeType + "-" + userId)
                .status(PostStatus.PUBLISHED)
                .build());
    }

    private TimelinePostEntity savePinned(PostScopeType scopeType, Long scopeId, UUID scopeVillageId, Long userId) {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scopeVillageId(scopeVillageId)
                .userId(userId)
                .content("w6-pinned-" + scopeType + "-" + userId)
                .status(PostStatus.PUBLISHED)
                .isPinned(true)
                .build());
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * 投稿作成リクエストを組み立てる。{@link CreatePostRequest} は全フィールド final の
     * イミュータブル DTO（{@code @JsonCreator} つき）でセッターを持たないため、
     * 12 引数の完全コンストラクタを使う。
     */
    private static CreatePostRequest createRequest(String scopeType, String scopeId, String content) {
        return new CreatePostRequest(
                content, scopeType, scopeId,
                null, null, null, null, null, null, null, null, null);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 網羅性そのもの: 8 値のいずれも「無検証で素通し」しないこと
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ディスパッチ網羅性（全8値）")
    class Exhaustiveness {

        /**
         * 部外者が全 8 値の scope を任意に指定してフィードを引いたとき、
         * <b>PUBLIC 以外は必ず例外で拒否される</b>ことを固定する。
         *
         * <p>これが本 Wave の核心。従来は TEAM / ORGANIZATION 以外が無検証で通過していた。</p>
         */
        @ParameterizedTest(name = "部外者の scopeType={0} フィード取得")
        @EnumSource(PostScopeType.class)
        @DisplayName("部外者はPUBLIC以外の全スコープ種別でフィードを取得できない")
        void 部外者はPUBLIC以外の全スコープでフィード取得できない(PostScopeType scopeType) {
            setAuthentication(USER_OUTSIDER);

            if (scopeType == PostScopeType.PUBLIC) {
                assertThatCode(() -> feedController.getFeed("PUBLIC", "0", null, 20))
                        .doesNotThrowAnyException();
                return;
            }

            // TEAM_A / ORG_A / 他人の PERSONAL / 村 / FRIEND_* いずれも部外者には開かない。
            Long probeScopeId = switch (scopeType) {
                case TEAM -> TEAM_A;
                case ORGANIZATION -> ORG_A;
                case PERSONAL -> USER_TEAM_A_MEMBER;
                default -> 0L;
            };

            assertThatThrownBy(() ->
                    feedController.getFeed(scopeType.name(), probeScopeId.toString(), null, 20))
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * ピン留め一覧も同じディスパッチを通ることを固定する
         * （{@code getFeed} は内部で pinned も引くため、片側だけ塞がる事故を防ぐ）。
         */
        @ParameterizedTest(name = "部外者の scopeType={0} ピン留め取得")
        @EnumSource(PostScopeType.class)
        @DisplayName("部外者はPUBLIC以外の全スコープ種別でピン留めを取得できない")
        void 部外者はPUBLIC以外の全スコープでピン留め取得できない(PostScopeType scopeType) {
            setAuthentication(USER_OUTSIDER);

            if (scopeType == PostScopeType.PUBLIC) {
                assertThatCode(() -> feedController.getPinnedPosts("PUBLIC", 0L))
                        .doesNotThrowAnyException();
                return;
            }

            Long probeScopeId = switch (scopeType) {
                case TEAM -> TEAM_A;
                case ORGANIZATION -> ORG_A;
                case PERSONAL -> USER_TEAM_A_MEMBER;
                default -> 0L;
            };

            assertThatThrownBy(() -> feedController.getPinnedPosts(scopeType.name(), probeScopeId))
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * 書き込み経路も同じディスパッチを通ることを固定する。
         * 読み取りだけ塞いで書き込みが空くのは本戦役で繰り返し起きた取りこぼし。
         *
         * <p>VILLAGE だけは例外で、{@code TimelinePostService#checkWriteScope} が
         * 下流の {@code validatePostingIdentity}（投稿主体単位の検証）へ委譲する。
         * 本ケースでは村 ID を渡していないため {@code VILLAGE_NOT_FOUND} で弾かれる。
         * いずれにせよ「部外者が書き込めない」ことは満たされる。</p>
         */
        @ParameterizedTest(name = "部外者の scopeType={0} 投稿作成")
        @EnumSource(PostScopeType.class)
        @DisplayName("部外者はPUBLIC以外の全スコープ種別へ投稿を作成できない")
        void 部外者はPUBLIC以外の全スコープへ投稿作成できない(PostScopeType scopeType) {
            setAuthentication(USER_OUTSIDER);

            if (scopeType == PostScopeType.PUBLIC) {
                return; // PUBLIC への投稿は正常系テストで別途固定する
            }

            Long probeScopeId = switch (scopeType) {
                case TEAM -> TEAM_A;
                case ORGANIZATION -> ORG_A;
                case PERSONAL -> USER_TEAM_A_MEMBER;
                default -> 0L;
            };

            CreatePostRequest req = createRequest(scopeType.name(), probeScopeId.toString(), "越境書き込みの試み");

            assertThatThrownBy(() -> postController.createPost(req))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("未知のスコープ種別文字列は500ではなくBusinessExceptionで拒否される")
        void 未知のスコープ種別文字列は業務例外で拒否される() {
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> feedController.getFeed("NOT_A_REAL_SCOPE", "0", null, 20))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PERSONAL — 従来は無検証で素通ししていた
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PERSONAL スコープ")
    class Personal {

        @Test
        @DisplayName("他人のPERSONALフィードを指定すると403（COMMON_002）")
        void 他人のPERSONALフィードは403() {
            savePost(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER);
            setAuthentication(USER_OUTSIDER);

            assertForbidden(() ->
                    feedController.getFeed("PERSONAL", USER_TEAM_A_MEMBER.toString(), null, 20));
        }

        @Test
        @DisplayName("[正常系] 本人は自分のPERSONALフィードを取得できる")
        void 本人は自分のPERSONALフィードを取得できる() {
            Long postId = savePost(
                    PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("PERSONAL", USER_TEAM_A_MEMBER.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }

        @Test
        @DisplayName("他人のPERSONALピン留めを指定すると403（COMMON_002）")
        void 他人のPERSONALピン留めは403() {
            savePinned(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER);
            setAuthentication(USER_OUTSIDER);

            assertForbidden(() -> feedController.getPinnedPosts("PERSONAL", USER_TEAM_A_MEMBER));
        }

        @Test
        @DisplayName("[正常系] 本人は自分のPERSONALピン留めを取得できる")
        void 本人は自分のPERSONALピン留めを取得できる() {
            Long pinnedId = savePinned(
                    PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getPinnedPosts("PERSONAL", USER_TEAM_A_MEMBER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData())
                    .extracting(PostResponse::getId).contains(pinnedId);
        }

        @Test
        @DisplayName("他人のPERSONALスコープへ投稿を作成すると403（COMMON_002）")
        void 他人のPERSONALへの投稿作成は403() {
            setAuthentication(USER_OUTSIDER);

            CreatePostRequest req = createRequest("PERSONAL", USER_TEAM_A_MEMBER.toString(), "他人の個人スコープへの投稿");

            assertForbidden(() -> postController.createPost(req));
        }

        @Test
        @DisplayName("[正常系] 本人は自分のPERSONALスコープへ投稿を作成できる")
        void 本人は自分のPERSONALへ投稿作成できる() {
            setAuthentication(USER_TEAM_A_MEMBER);

            CreatePostRequest req = createRequest("PERSONAL", USER_TEAM_A_MEMBER.toString(), "自分の個人スコープへの投稿");

            ResponseEntity<ApiResponse<PostResponse>> response = postController.createPost(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getId()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // FRIEND_* — 汎用タイムライン経路では fail-closed
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FRIEND_* スコープ（汎用経路では fail-closed）")
    class FriendScopes {

        @Test
        @DisplayName("FRIEND_*フィードはチーム所属者であっても汎用経路では取得できない")
        void FRIEND各種フィードは汎用経路で取得できない() {
            setAuthentication(USER_TEAM_A_MEMBER);

            for (PostScopeType scopeType : FRIEND_SCOPES) {
                savePost(scopeType, TEAM_A, null, USER_TEAM_A_MEMBER);
                assertThatThrownBy(() ->
                        feedController.getFeed(scopeType.name(), TEAM_A.toString(), null, 20))
                        .as("scopeType=%s のフィードは fail-closed であること", scopeType)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("FRIEND_*ピン留めはチーム所属者であっても汎用経路では取得できない")
        void FRIEND各種ピン留めは汎用経路で取得できない() {
            setAuthentication(USER_TEAM_A_MEMBER);

            for (PostScopeType scopeType : FRIEND_SCOPES) {
                assertThatThrownBy(() -> feedController.getPinnedPosts(scopeType.name(), TEAM_A))
                        .as("scopeType=%s のピン留めは fail-closed であること", scopeType)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("FRIEND_*スコープへの投稿作成は汎用経路では拒否される")
        void FRIEND各種への投稿作成は拒否される() {
            setAuthentication(USER_TEAM_A_MEMBER);

            for (PostScopeType scopeType : FRIEND_SCOPES) {
                CreatePostRequest req = createRequest(scopeType.name(), TEAM_A.toString(), "汎用経路からのフレンドスコープ投稿");

                assertThatThrownBy(() -> postController.createPost(req))
                        .as("scopeType=%s への投稿は fail-closed であること", scopeType)
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("[BOLA] FRIEND_FORWARD投稿の詳細は転送先チーム非所属者には見えない")
        void FRIEND_FORWARD詳細は非所属者に見えない() {
            Long postId = savePost(
                    PostScopeType.FRIEND_FORWARD, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("[正常系] FRIEND_FORWARD投稿の詳細は転送先チーム所属者には見える")
        void FRIEND_FORWARD詳細は所属者に見える() {
            Long postId = savePost(
                    PostScopeType.FRIEND_FORWARD, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            assertThatCode(() -> postController.getPost(postId)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("[BOLA] FRIEND_TEAM/FRIEND_ARCHIVE投稿の詳細は誰にも開かない（生成経路が無く fail-closed）")
        void FRIEND_TEAMとARCHIVEの詳細は誰にも開かない() {
            Long friendTeamPost = savePost(
                    PostScopeType.FRIEND_TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            Long friendArchivePost = savePost(
                    PostScopeType.FRIEND_ARCHIVE, TEAM_A, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_TEAM_A_MEMBER);

            assertThatThrownBy(() -> postController.getPost(friendTeamPost))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> postController.getPost(friendArchivePost))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // VILLAGE — scope_id 経由は fail-closed / scopeVillageId 経由が正路
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("VILLAGE スコープ")
    class Village {

        @Test
        @DisplayName("[正常系] 村人はscopeVillageId経由で村フィードを取得できる")
        void 村人は村フィードを取得できる() {
            Long postId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_VILLAGE_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("VILLAGE", "0", villageId, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }

        @Test
        @DisplayName("非村人はscopeVillageId経由の村フィードを取得できない")
        void 非村人は村フィードを取得できない() {
            savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER);
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> feedController.getFeed("VILLAGE", "0", villageId, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.NOT_MEMBER));
        }

        /**
         * 村のピン留めは {@code scope_id} が常に 0 のため、従来は全村のピン留めが
         * 種別一致だけで混在していた。村 ID を複合キーとして引くことで根治したことを固定する。
         */
        @Test
        @DisplayName("[正常系] 村人のピン留め取得は自村のピン留めのみを返し他村のものは混ざらない")
        void 村ピン留めは自村のみ返す() {
            Long myVillagePinned =
                    savePinned(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();

            VillageEntity otherVillage = villageRepository.save(VillageEntity.builder()
                    .slug("w6t-other-village-" + System.nanoTime())
                    .name("他村")
                    .type(VillageType.COMMUNITY)
                    .joinPolicy(VillageJoinPolicy.FREE)
                    .visibility(VillageVisibility.PUBLIC)
                    .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                    .build());
            Long otherVillagePinned =
                    savePinned(PostScopeType.VILLAGE, 0L, otherVillage.getId(), USER_OUTSIDER).getId();

            setAuthentication(USER_VILLAGE_MEMBER);
            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("VILLAGE", "0", villageId, 20);

            List<Long> pinnedIds = response.getBody().getData().getPinned().stream()
                    .map(PostResponse::getId).toList();
            assertThat(pinnedIds).contains(myVillagePinned);
            assertThat(pinnedIds).doesNotContain(otherVillagePinned);
        }

        @Test
        @DisplayName("scopeVillageId無しのVILLAGE指定（/pinned 経路）は fail-closed で拒否される")
        void 村ID無しのVILLAGEピン留め指定は拒否される() {
            setAuthentication(USER_VILLAGE_MEMBER);

            assertThatThrownBy(() -> feedController.getPinnedPosts("VILLAGE", 0L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // TEAM / ORGANIZATION / PUBLIC — 既存挙動の非回帰
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TEAM / ORGANIZATION / PUBLIC（非回帰）")
    class ExistingScopes {

        @Test
        @DisplayName("[正常系] チームメンバーはTEAMフィードとピン留めを取得できる")
        void チームメンバーはTEAMフィードとピン留めを取得できる() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            Long pinnedId = savePinned(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("TEAM", TEAM_A.toString(), null, 20);

            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
            assertThat(response.getBody().getData().getPinned())
                    .extracting(PostResponse::getId).contains(pinnedId);
        }

        @Test
        @DisplayName("非メンバーのTEAMフィードは403（COMMON_002）")
        void 非メンバーのTEAMフィードは403() {
            setAuthentication(USER_OUTSIDER);
            assertForbidden(() -> feedController.getFeed("TEAM", TEAM_A.toString(), null, 20));
        }

        @Test
        @DisplayName("[正常系] 組織メンバーはORGANIZATIONフィードを取得できる")
        void 組織メンバーはORGフィードを取得できる() {
            Long postId = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();
            setAuthentication(USER_ORG_A_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("ORGANIZATION", ORG_A.toString(), null, 20);

            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }

        @Test
        @DisplayName("非メンバーのORGANIZATIONフィードは403（COMMON_002）")
        void 非メンバーのORGフィードは403() {
            setAuthentication(USER_OUTSIDER);
            assertForbidden(() -> feedController.getFeed("ORGANIZATION", ORG_A.toString(), null, 20));
        }

        @Test
        @DisplayName("[正常系] PUBLICフィードは所属を問わず取得でき、投稿も作成できる")
        void PUBLICは誰でも読み書きできる() {
            Long postId = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("PUBLIC", "0", null, 20);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);

            CreatePostRequest req = createRequest("PUBLIC", "0", "公開投稿");
            assertThat(postController.createPost(req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }
}
