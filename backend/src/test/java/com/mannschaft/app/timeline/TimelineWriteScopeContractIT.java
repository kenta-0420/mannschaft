package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.controller.TimelinePostController;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * 認可根治 Wave6 追加戦: timeline 書き込み経路（投稿作成）の <b>実効スコープ</b> 認可契約テスト（試練）。
 *
 * <p>投稿作成はリクエストが申告した {@code scopeType}/{@code scopeId} ではなく、
 * <b>実際に保存されるスコープ</b>で認可されねばならない。リプライ（{@code parentId} 指定）では
 * スコープを親投稿から継承するため、申告値と実効値が食い違いうる。本 IT はその
 * <b>継承ベクタ</b>を明示的に突き、「継承後の実効スコープに到達できない利用者は書き込めない」ことと
 * 「当該スコープの正当な利用者は従来どおり書き込める」ことの両方を固定する。</p>
 *
 * <p>金型: {@link TimelineReadScopeContractIT}（Controller 直接 Autowire + SecurityContext 差し替え +
 * JPA リポジトリ直接 save でのシード方式）。roles / user_roles は test profile で Flyway 初期シードが
 * 無効なため、村への代理投稿検証に必要な行のみ native SQL で手動 seed する。</p>
 *
 * <p>対象EP: {@code TimelinePostController#createPost}。</p>
 */
@DisplayName("timeline 書き込み経路 実効スコープ認可契約テスト（認可根治 Wave6 追加戦）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineWriteScopeContractIT extends AbstractMySqlIntegrationTest {

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

    @PersistenceContext
    private EntityManager em;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    private static final Long USER_TEAM_A_MEMBER = 92_301L;
    private static final Long USER_ORG_A_MEMBER = 92_302L;
    private static final Long USER_OUTSIDER = 92_303L;
    private static final Long USER_VILLAGE_MEMBER = 92_304L;
    private static final Long USER_POST_OWNER = 92_305L;
    /** 村メンバーかつ「村メンバーであるチーム」の ADMIN。チーム代理投稿の正常系で使う。 */
    private static final Long USER_TEAM_REPRESENTATIVE = 92_306L;

    // --- 所属スコープ ---
    private static final Long TEAM_A = 70_301L;
    private static final Long ORG_A = 80_301L;
    /** 村のメンバーとして登録するチーム（代理投稿の主体）。 */
    private static final Long PROXY_TEAM = 70_302L;

    private UUID villageId;

    @BeforeEach
    void setUp() {
        membershipRepository.save(membership(USER_TEAM_A_MEMBER, ScopeType.TEAM, TEAM_A));
        membershipRepository.save(membership(USER_ORG_A_MEMBER, ScopeType.ORGANIZATION, ORG_A));

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("w6w-village-" + System.nanoTime())
                .name("書き込み認可契約テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .build());
        villageId = village.getId();
        villageMembershipRepository.save(villageMembership(VillageSubjectType.USER, USER_VILLAGE_MEMBER));
        villageMembershipRepository.save(villageMembership(VillageSubjectType.USER, USER_TEAM_REPRESENTATIVE));
        villageMembershipRepository.save(villageMembership(VillageSubjectType.TEAM, PROXY_TEAM));
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

    private VillageMembershipEntity villageMembership(VillageSubjectType subjectType, Long subjectId) {
        return VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private TimelinePostEntity savePost(PostScopeType scopeType, Long scopeId, UUID scopeVillageId, Long userId) {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scopeVillageId(scopeVillageId)
                .userId(userId)
                .postedAsType(PostedAsType.USER)
                .content("parent-" + scopeType + "-" + userId)
                .status(PostStatus.PUBLISHED)
                .build());
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** リプライリクエスト（FE と同じく content と parentId のみ・スコープは申告しない）。 */
    private CreatePostRequest reply(Long parentId) {
        return new CreatePostRequest("リプライ本文", null, (Long) null,
                "USER", null, parentId, null, null, null, null);
    }

    /** スコープを偽って申告するリプライリクエスト（PUBLIC を申告しつつ親から継承させる）。 */
    private CreatePostRequest replyDeclaringPublic(Long parentId) {
        return new CreatePostRequest("リプライ本文", "PUBLIC", 0L,
                "USER", null, parentId, null, null, null, null);
    }

    /** 実際に保存されたリプライ件数（親 ID 指定）。 */
    private long replyCount(Long parentId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM timeline_posts WHERE parent_id = :pid AND deleted_at IS NULL")
                .setParameter("pid", parentId)
                .getSingleResult()).longValue();
    }

    private void insertRole(String name) {
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", name)
                .setParameter("priority", 100)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long userId, Long roleIdParam, Long teamIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, NULL, NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam)
                .executeUpdate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 継承ベクタの封鎖（本件の拒否）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parentId 継承ベクタ（非メンバーの書き込み封鎖）")
    class InheritedScopeDenied {

        @Test
        @DisplayName("非メンバーがTEAM投稿へリプライすると404（POST_NOT_FOUND）・投稿は保存されない")
        void 非メンバーのTEAMリプライは404() {
            Long parentId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.createPost(reply(parentId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(replyCount(parentId))
                    .as("拒否された場合はリプライが保存されていないこと")
                    .isZero();
        }

        @Test
        @DisplayName("PUBLICを申告してもTEAM投稿への継承リプライは404（申告値では認可されない）")
        void PUBLIC申告でも継承リプライは404() {
            Long parentId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.createPost(replyDeclaringPublic(parentId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(replyCount(parentId)).isZero();
        }

        @Test
        @DisplayName("非メンバーがORGANIZATION投稿へリプライすると404（POST_NOT_FOUND）")
        void 非メンバーのORGリプライは404() {
            Long parentId = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.createPost(replyDeclaringPublic(parentId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(replyCount(parentId)).isZero();
        }

        @Test
        @DisplayName("他人のPERSONAL投稿へリプライすると404（POST_NOT_FOUND）")
        void 他人のPERSONAL投稿へのリプライは404() {
            Long parentId = savePost(PostScopeType.PERSONAL, USER_POST_OWNER, null, USER_POST_OWNER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.createPost(reply(parentId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(replyCount(parentId)).isZero();
        }

        @Test
        @DisplayName("非村人がVILLAGE投稿へリプライするとNOT_MEMBER・投稿は保存されない")
        void 非村人のVILLAGEリプライは拒否される() {
            Long parentId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> postController.createPost(reply(parentId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.NOT_MEMBER));
            assertThat(replyCount(parentId)).isZero();
        }

        @Test
        @DisplayName("村人でも他チームを騙る主体でのVILLAGEリプライは拒否される（主体なりすまし封鎖）")
        void 主体を騙るVILLAGEリプライは拒否される() {
            Long parentId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_VILLAGE_MEMBER);

            CreatePostRequest req = new CreatePostRequest("なりすましリプライ", null, (Long) null,
                    "TEAM", PROXY_TEAM, parentId, null, null, null, null);

            assertThatThrownBy(() -> postController.createPost(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN));
            assertThat(replyCount(parentId)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 正常系（リプライは日常機能・壊さないことを固定する）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("正常系（当該スコープのメンバーはリプライできる）")
    class InheritedScopeAllowed {

        @Test
        @DisplayName("TEAMメンバーはTEAM投稿へリプライでき、親のTEAMスコープを継承する")
        void TEAMメンバーはリプライできる() {
            Long parentId = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.createPost(reply(parentId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
            TimelinePostEntity saved = postRepository.findById(response.getBody().getData().getId())
                    .orElseThrow();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.TEAM);
            assertThat(saved.getScopeId()).isEqualTo(TEAM_A);
        }

        @Test
        @DisplayName("ORGANIZATIONメンバーはORGANIZATION投稿へリプライできる")
        void ORGメンバーはリプライできる() {
            Long parentId = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();
            setAuthentication(USER_ORG_A_MEMBER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.createPost(reply(parentId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
            TimelinePostEntity saved = postRepository.findById(response.getBody().getData().getId())
                    .orElseThrow();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.ORGANIZATION);
            assertThat(saved.getScopeId()).isEqualTo(ORG_A);
        }

        @Test
        @DisplayName("PUBLIC投稿へは所属を問わずリプライできる")
        void PUBLIC投稿へは誰でもリプライできる() {
            Long parentId = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();
            setAuthentication(USER_OUTSIDER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.createPost(reply(parentId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("本人は自分のPERSONAL投稿へリプライできる")
        void 本人はPERSONAL投稿へリプライできる() {
            Long parentId = savePost(PostScopeType.PERSONAL, USER_POST_OWNER, null, USER_POST_OWNER).getId();
            setAuthentication(USER_POST_OWNER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.createPost(reply(parentId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("村人はVILLAGE投稿へリプライでき、親の村スコープを継承する")
        void 村人はVILLAGE投稿へリプライできる() {
            Long parentId = savePost(PostScopeType.VILLAGE, 0L, villageId, USER_VILLAGE_MEMBER).getId();
            setAuthentication(USER_VILLAGE_MEMBER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.createPost(reply(parentId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
            TimelinePostEntity saved = postRepository.findById(response.getBody().getData().getId())
                    .orElseThrow();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.VILLAGE);
            assertThat(saved.getScopeVillageId()).isEqualTo(villageId);
        }

        @Test
        @DisplayName("チーム代理投稿とそのリプライは従来どおり成立する（村メンバーのチームADMIN）")
        void チーム代理投稿とリプライは成立する() {
            insertRole("ADMIN");
            insertUserRole(USER_TEAM_REPRESENTATIVE, roleId("ADMIN"), PROXY_TEAM);
            setAuthentication(USER_TEAM_REPRESENTATIVE);

            // 1) チーム主体で村へ新規投稿できること
            CreatePostRequest proxyPost = new CreatePostRequest("チームからの村への告知", "VILLAGE", "0",
                    "TEAM", PROXY_TEAM, null, null, null, null, null, null, villageId);
            ResponseEntity<ApiResponse<PostResponse>> created = postController.createPost(proxyPost);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long parentId = created.getBody().getData().getId();

            // 2) そのチーム代理投稿へ、同じチーム主体でリプライできること
            CreatePostRequest proxyReply = new CreatePostRequest("チームからのリプライ", null, (Long) null,
                    "TEAM", PROXY_TEAM, parentId, null, null, null, null);
            ResponseEntity<ApiResponse<PostResponse>> repliedAsTeam = postController.createPost(proxyReply);

            assertThat(repliedAsTeam.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replyCount(parentId)).isEqualTo(1L);
            TimelinePostEntity saved = postRepository.findById(repliedAsTeam.getBody().getData().getId())
                    .orElseThrow();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.VILLAGE);
            assertThat(saved.getScopeVillageId()).isEqualTo(villageId);
            assertThat(saved.getPostedAsType()).isEqualTo(PostedAsType.TEAM);
            assertThat(saved.getPostedAsId()).isEqualTo(PROXY_TEAM);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リポスト（参照先の可視性）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リポスト（参照先投稿の可視性）")
    class Repost {

        @Test
        @DisplayName("見えないTEAM投稿はリポストできない（404・リポスト数も増えない）")
        void 見えない投稿はリポストできない() {
            TimelinePostEntity original = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER);
            setAuthentication(USER_OUTSIDER);

            CreatePostRequest req = new CreatePostRequest("リポスト試み", "PUBLIC", 0L,
                    "USER", null, null, original.getId(), null, null, null);

            assertThatThrownBy(() -> postController.createPost(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(postRepository.findById(original.getId()).orElseThrow().getRepostCount())
                    .as("拒否された場合はリポスト数が増えていないこと")
                    .isZero();
        }

        @Test
        @DisplayName("見えるPUBLIC投稿は従来どおりリポストできる")
        void 見える投稿はリポストできる() {
            TimelinePostEntity original = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER);
            setAuthentication(USER_OUTSIDER);

            CreatePostRequest req = new CreatePostRequest("リポスト", "PUBLIC", 0L,
                    "USER", null, null, original.getId(), null, null, null);

            ResponseEntity<ApiResponse<PostResponse>> response = postController.createPost(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(postRepository.findById(original.getId()).orElseThrow().getRepostCount())
                    .isEqualTo(1);
        }
    }
}
