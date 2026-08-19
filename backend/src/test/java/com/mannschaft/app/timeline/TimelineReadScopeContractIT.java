package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.controller.TimelineFeedController;
import com.mannschaft.app.timeline.controller.TimelinePostController;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 認可根治戦役 Wave3-B7-timeline: timeline 読取経路（feed/userPosts/pinned/postDetail/replies）の
 * scope membership 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B7-timeline節）・{@code AccessControlService}
 * （{@code checkMembership}/{@code isMember}）・{@code PostingIdentityService#isUserVillageMember}。
 * 金型: {@code TimelineMyFeedControllerIntegrationTest}（Controller 直接 Autowire + SecurityContext
 * 差し替え + JPA リポジトリ直接 save でのシード方式）。SEARCH_QUERY（全文検索）の漏洩防止は
 * {@link TimelineSearchScopeContractIT} に分離する（本 IT は個別 EP の scope 認可が対象）。</p>
 *
 * <p>対象EP: {@code TimelineFeedController#getFeed}（TEAM/ORGANIZATION/VILLAGE 非メンバー拒否）・
 * {@code #getUserPosts}（他人閲覧時の可視 scope 限定）・{@code #getPinnedPosts}（同上）・
 * {@code TimelinePostController#getPost}（BOLA: post 自身の scope で判定）・
 * {@code #getReplies}（BOLA: 親投稿の scope で判定）。</p>
 */
@DisplayName("timeline 読取経路 scope 認可契約テスト（認可根治 Wave3-B7-timeline）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineReadScopeContractIT extends AbstractMySqlIntegrationTest {

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

    @Autowired
    private com.mannschaft.app.organization.repository.OrganizationRepository organizationRepository;

    @Autowired
    private com.mannschaft.app.timeline.repository.UserMuteRepository muteRepository;

    /** 返信の生成経路（親からのスコープ／配信範囲の継承）を実物で通すために使う。 */
    @Autowired
    private com.mannschaft.app.timeline.service.TimelinePostService postService;

    /** 組織 slug の一意性確保用の連番（slug は 30 文字・UNIQUE）。 */
    private int deliveryOrgSeq = 0;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    private static final Long USER_TEAM_A_MEMBER = 92_201L;
    private static final Long USER_ORG_A_MEMBER = 92_202L;
    private static final Long USER_OUTSIDER = 92_203L;
    private static final Long USER_VILLAGE_MEMBER = 92_204L;
    private static final Long USER_POST_OWNER = 92_205L;

    // --- 所属スコープ ---
    private static final Long TEAM_A = 70_201L;
    private static final Long TEAM_B = 70_202L;
    private static final Long ORG_A = 80_201L;

    private UUID villageId;

    @BeforeEach
    void setUp() {
        membershipRepository.save(membership(USER_TEAM_A_MEMBER, ScopeType.TEAM, TEAM_A));
        membershipRepository.save(membership(USER_ORG_A_MEMBER, ScopeType.ORGANIZATION, ORG_A));

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("b7t-village-" + System.nanoTime())
                .name("認可契約テスト村")
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
                .content("post-" + scopeType + "-" + userId)
                .status(PostStatus.PUBLISHED)
                .build());
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // getFeed
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getFeed")
    class GetFeed {

        @Test
        @DisplayName("非メンバーがTEAMフィードを取得すると403（COMMON_002）")
        void 非メンバーのTEAMフィードは403() {
            savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER);
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> feedController.getFeed("TEAM", TEAM_A.toString(), null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("正当メンバーはTEAMフィードを取得できる")
        void 正当メンバーはTEAMフィードを取得できる() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("TEAM", TEAM_A.toString(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }

        @Test
        @DisplayName("非メンバーがVILLAGEフィードを取得すると404相当（VILLAGE_007 NOT_MEMBER）")
        void 非メンバーのVILLAGEフィードはNOT_MEMBER() {
            savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER);
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> feedController.getFeed("VILLAGE", "0", villageId, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.NOT_MEMBER));
        }

        @Test
        @DisplayName("正当な村人はVILLAGEフィードを取得できる")
        void 正当な村人はVILLAGEフィードを取得できる() {
            Long postId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_VILLAGE_MEMBER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("VILLAGE", "0", villageId, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }

        @Test
        @DisplayName("PUBLICフィードは所属を問わず取得できる")
        void PUBLICフィードは所属を問わず取得できる() {
            Long postId = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            ResponseEntity<TimelineFeedResponse> response =
                    feedController.getFeed("PUBLIC", "0", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // getUserPosts（BOLA: 対象userの全scope投稿がscope無視で漏洩していた）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getUserPosts")
    class GetUserPosts {

        @Test
        @DisplayName("本人が自分の投稿一覧を見ると全scopeの投稿が出る（TEAM/PERSONAL含む）")
        void 本人は全scope投稿を見られる() {
            Long teamPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            Long personalPost = savePost(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();
            Long publicPost = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_TEAM_A_MEMBER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getUserPosts(USER_TEAM_A_MEMBER, 50);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).extracting(PostResponse::getId)
                    .contains(teamPost, personalPost, publicPost);
        }

        @Test
        @DisplayName("[BOLA根治] 無関係な第三者が閲覧するとPUBLICのみ・TEAM/PERSONALは出ない")
        void 無関係な第三者はPUBLICのみ見える() {
            Long teamPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            Long personalPost = savePost(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();
            Long publicPost = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_OUTSIDER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getUserPosts(USER_TEAM_A_MEMBER, 50);

            List<Long> ids = response.getBody().getData().stream().map(PostResponse::getId).toList();
            assertThat(ids).contains(publicPost);
            assertThat(ids).doesNotContain(teamPost, personalPost);
        }

        @Test
        @DisplayName("[BOLA根治] 同じTEAMに所属する第三者はそのTEAM投稿は見えるがPERSONAL/別TEAMは見えない")
        void 同一チーム所属者は共有scopeのみ見える() {
            Long teamAPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            Long teamBPost = savePost(PostScopeType.TEAM, TEAM_B, null, USER_TEAM_A_MEMBER).getId();
            Long personalPost = savePost(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();

            // USER_ORG_A_MEMBER にも TEAM_A membership を追加（同一チームの別ユーザー役）
            membershipRepository.save(membership(USER_ORG_A_MEMBER, ScopeType.TEAM, TEAM_A));

            setAuthentication(USER_ORG_A_MEMBER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getUserPosts(USER_TEAM_A_MEMBER, 50);

            List<Long> ids = response.getBody().getData().stream().map(PostResponse::getId).toList();
            assertThat(ids).contains(teamAPost);
            assertThat(ids).doesNotContain(teamBPost, personalPost);
        }

        @Test
        @DisplayName("[BOLA根治] 同じ村に所属する第三者はそのVILLAGE投稿が見える")
        void 同一村所属者はVILLAGE投稿が見える() {
            Long villagePost = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();

            setAuthentication(USER_VILLAGE_MEMBER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getUserPosts(USER_VILLAGE_MEMBER, 50);

            assertThat(response.getBody().getData()).extracting(PostResponse::getId).contains(villagePost);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 配下配信の認可対称性 — 認AC-13〜15, 17, 18
    //
    // 配信範囲は「可視性の拡大」であるため、フィードに出る投稿は詳細取得・ユーザー投稿一覧でも
    // 到達できなければならない（フィードには出るのに直リンクは404、という非対称を禁じる）。
    // 逆に DIRECT の投稿は、どの経路からも子組織所属者に見えてはならない。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("配下配信の認可対称性")
    class DeliveryScopeAuthzSymmetry {

        private Long parentOrg;
        private Long childOrg;
        private static final Long USER_CHILD_ORG_MEMBER = 92_301L;

        @BeforeEach
        void setUpHierarchy() {
            parentOrg = saveOrgForDelivery(null);
            childOrg = saveOrgForDelivery(parentOrg);
            membershipRepository.save(membership(
                    USER_CHILD_ORG_MEMBER, ScopeType.ORGANIZATION, childOrg));
        }

        @Test
        @DisplayName("認AC-13 DESCENDANTS の投稿は受信者が getPost で取得できる（404にならない）")
        void deliveredPost_isReachableByDirectLink() {
            Long postId = saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);

            setAuthentication(USER_CHILD_ORG_MEMBER);
            ResponseEntity<ApiResponse<PostDetailResponse>> response = postController.getPost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(postId);
        }

        @Test
        @DisplayName("認AC-14 DIRECT の投稿を子組織所属者が直リンクで開くと404（POST_NOT_FOUND）")
        void directPost_isNotReachableByChildOrgMember() {
            Long postId = saveDeliveryPost(parentOrg, PostDeliveryScope.DIRECT);

            setAuthentication(USER_CHILD_ORG_MEMBER);

            assertThatThrownBy(() -> postController.getPost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("認AC-15/17 ユーザー投稿一覧: DESCENDANTS は出る・DIRECT は出ない")
        void userPosts_respectDeliveryScope() {
            Long delivered = saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);
            Long direct = saveDeliveryPost(parentOrg, PostDeliveryScope.DIRECT);

            setAuthentication(USER_CHILD_ORG_MEMBER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getUserPosts(USER_POST_OWNER, 50);

            List<Long> ids = response.getBody().getData().stream().map(PostResponse::getId).toList();
            assertThat(ids).contains(delivered);
            assertThat(ids).doesNotContain(direct);
        }

        @Test
        @DisplayName("認AC-31 配信で届いた投稿への返信が、返信者自身から getPost で取得できる（404にならない）")
        void replyToDeliveredPost_isReachableByReplier() {
            Long parentPostId = saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);

            setAuthentication(USER_CHILD_ORG_MEMBER);
            // 返信は親のスコープ（上位組織 P）を継承する。delivery_scope も継承しないと
            // scope_id=P / delivery_scope=DIRECT の行になり、返信者自身から 404 になる。
            PostResponse reply = postService.createPost(new CreatePostRequest(
                    "配信投稿への返信", null, (Long) null, null, null,
                    parentPostId, null, null, null, null), USER_CHILD_ORG_MEMBER);

            ResponseEntity<ApiResponse<PostDetailResponse>> response =
                    postController.getPost(reply.getId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(reply.getId());
        }

        @Test
        @DisplayName("認AC-31 同じ配信範囲の他ユーザーからも、その返信を getPost で取得できる")
        void replyToDeliveredPost_isReachableByOtherDeliveredUser() {
            Long otherChildOrgMember = 92_302L;
            membershipRepository.save(membership(
                    otherChildOrgMember, ScopeType.ORGANIZATION, childOrg));
            Long parentPostId = saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);

            setAuthentication(USER_CHILD_ORG_MEMBER);
            PostResponse reply = postService.createPost(new CreatePostRequest(
                    "配信投稿への返信", null, (Long) null, null, null,
                    parentPostId, null, null, null, null), USER_CHILD_ORG_MEMBER);

            setAuthentication(otherChildOrgMember);
            ResponseEntity<ApiResponse<PostDetailResponse>> response =
                    postController.getPost(reply.getId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(reply.getId());
        }

        @Test
        @DisplayName("認AC-31 陰性対照: 配信範囲外のユーザーからはその返信も見えない（404）")
        void replyToDeliveredPost_isNotReachableByOutsider() {
            Long parentPostId = saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);

            setAuthentication(USER_CHILD_ORG_MEMBER);
            PostResponse reply = postService.createPost(new CreatePostRequest(
                    "配信投稿への返信", null, (Long) null, null, null,
                    parentPostId, null, null, null, null), USER_CHILD_ORG_MEMBER);

            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(reply.getId()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("認AC-18 配信は入場権ではない: 上位組織のタイムライン画面は依然403")
        void delivery_doesNotGrantFeedEntry() {
            saveDeliveryPost(parentOrg, PostDeliveryScope.DESCENDANTS);

            setAuthentication(USER_CHILD_ORG_MEMBER);

            assertThatThrownBy(() ->
                    feedController.getFeed("ORGANIZATION", parentOrg.toString(), null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ミュートは認可ではなく表示設定 — 認AC-25, 26
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ミュートと認可の分離")
    class MuteIsNotAuthorization {

        @Test
        @DisplayName("認AC-25 ミュート中スコープの投稿も getPost では 200 で取得できる")
        void mutedScopePost_isStillReachable() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            muteRepository.save(UserMuteEntity.builder()
                    .userId(USER_TEAM_A_MEMBER).mutedType("TEAM").mutedId(TEAM_A).build());

            setAuthentication(USER_TEAM_A_MEMBER);
            ResponseEntity<ApiResponse<PostDetailResponse>> response = postController.getPost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(postId);
        }

        @Test
        @DisplayName("認AC-26 自分のミュートは他人のフィードに影響しない")
        void myMute_doesNotAffectOthersFeed() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            // USER_ORG_A_MEMBER も TEAM_A に所属させ、USER_TEAM_A_MEMBER だけが TEAM_A をミュートする
            membershipRepository.save(membership(USER_ORG_A_MEMBER, ScopeType.TEAM, TEAM_A));
            muteRepository.save(UserMuteEntity.builder()
                    .userId(USER_TEAM_A_MEMBER).mutedType("TEAM").mutedId(TEAM_A).build());

            setAuthentication(USER_TEAM_A_MEMBER);
            assertThat(feedController.getMyFeed(null, 50).getBody().getData().getPosts())
                    .extracting(PostResponse::getId).doesNotContain(postId);

            setAuthentication(USER_ORG_A_MEMBER);
            assertThat(feedController.getMyFeed(null, 50).getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(postId);
        }
    }

    // --- 配下配信テスト用ヘルパー ---

    /** 組織を 1 件作る（親 ID 指定で子組織）。ID は採番されるため戻り値を使う。 */
    private Long saveOrgForDelivery(Long parentOrgId) {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("b7dlv" + (deliveryOrgSeq++) + "-" + (System.nanoTime() % 1_000_000L))
                .name("配下配信契約テスト組織")
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrgId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(false)
                .build()).getId();
    }

    /** 組織スコープ投稿を配信範囲付きで作る（投稿者は USER_POST_OWNER）。 */
    private Long saveDeliveryPost(Long orgId, PostDeliveryScope deliveryScope) {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.ORGANIZATION)
                .scopeId(orgId)
                .userId(USER_POST_OWNER)
                .content("delivery-" + orgId + "-" + deliveryScope)
                .status(PostStatus.PUBLISHED)
                .deliveryScope(deliveryScope)
                .build()).getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // getPinnedPosts
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPinnedPosts")
    class GetPinnedPosts {

        @Test
        @DisplayName("非メンバーのORGANIZATIONピン留め取得は403（COMMON_002）")
        void 非メンバーのORGピン留め取得は403() {
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> feedController.getPinnedPosts("ORGANIZATION", ORG_A))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("正当メンバーはORGANIZATIONピン留めを取得できる")
        void 正当メンバーはORGピン留めを取得できる() {
            TimelinePostEntity pinned = postRepository.save(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.ORGANIZATION)
                    .scopeId(ORG_A)
                    .userId(USER_ORG_A_MEMBER)
                    .content("ピン留め投稿")
                    .status(PostStatus.PUBLISHED)
                    .isPinned(true)
                    .build());

            setAuthentication(USER_ORG_A_MEMBER);
            ResponseEntity<ApiResponse<List<PostResponse>>> response =
                    feedController.getPinnedPosts("ORGANIZATION", ORG_A);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).extracting(PostResponse::getId)
                    .contains(pinned.getId());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // getPost（投稿詳細・BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPost（投稿詳細・BOLA）")
    class GetPost {

        @Test
        @DisplayName("[BOLA] TEAMスコープ投稿を非メンバーが取得すると404（POST_NOT_FOUND）")
        void TEAMスコープ投稿を非メンバーが取得すると404() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("正当メンバーはTEAMスコープ投稿詳細を取得できる")
        void 正当メンバーはTEAMスコープ投稿詳細を取得できる() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<PostDetailResponse>> response = postController.getPost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(postId);
        }

        @Test
        @DisplayName("[BOLA] PERSONALスコープ投稿を本人以外が取得すると404（POST_NOT_FOUND）")
        void PERSONALスコープ投稿を本人以外が取得すると404() {
            Long postId = savePost(PostScopeType.PERSONAL, USER_POST_OWNER, null, USER_POST_OWNER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("[BOLA] VILLAGEスコープ投稿を非村人が取得すると404（POST_NOT_FOUND）")
        void VILLAGEスコープ投稿を非村人が取得すると404() {
            Long postId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("PUBLICスコープ投稿は誰でも取得できる")
        void PUBLICスコープ投稿は誰でも取得できる() {
            Long postId = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            ResponseEntity<ApiResponse<PostDetailResponse>> response = postController.getPost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("存在しない投稿IDは404（POST_NOT_FOUND、既存挙動の非回帰）")
        void 存在しない投稿は404() {
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getPost(999_999_999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // getReplies（BOLA: 親投稿のscope未検証だった）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getReplies（親投稿のBOLA）")
    class GetReplies {

        @Test
        @DisplayName("[BOLA] 親投稿がTEAMスコープで非メンバーが取得すると404（POST_NOT_FOUND）")
        void 親投稿TEAMで非メンバーは404() {
            TimelinePostEntity parent = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER);
            postRepository.save(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(TEAM_A)
                    .userId(USER_TEAM_A_MEMBER)
                    .parentId(parent.getId())
                    .content("返信")
                    .status(PostStatus.PUBLISHED)
                    .build());

            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.getReplies(parent.getId(), null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("正当メンバーは親投稿がTEAMスコープでもリプライを取得できる")
        void 正当メンバーはTEAMリプライを取得できる() {
            TimelinePostEntity parent = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER);
            Long replyId = postRepository.save(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(TEAM_A)
                    .userId(USER_TEAM_A_MEMBER)
                    .parentId(parent.getId())
                    .content("返信")
                    .status(PostStatus.PUBLISHED)
                    .build()).getId();

            setAuthentication(USER_TEAM_A_MEMBER);
            ResponseEntity<TimelineFeedResponse> response =
                    postController.getReplies(parent.getId(), null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getPosts())
                    .extracting(PostResponse::getId).contains(replyId);
        }
    }
}
