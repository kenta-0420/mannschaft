package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.timeline.PostDeliveryScope;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.repository.UserMuteRepository;
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
 * {@link TimelineFeedController#getMyFeed} 個人ダッシュボード集約タイムライン統合テスト。
 *
 * <p>Testcontainers MySQL + {@link AbstractMySqlIntegrationTest} で ApplicationContext を共有する。
 * Controller を直接 Autowire し、SecurityContext に認証情報を設定して受け入れ条件を検証する。</p>
 *
 * <p>受け入れ条件（殿の確定仕様 a〜f）:</p>
 * <ul>
 *   <li>AC-1 未認証 → 401（COMMON_000 / BusinessException）</li>
 *   <li>AC-2 メンバーの所属チーム投稿が出る</li>
 *   <li>AC-3 メンバーの所属組織投稿が出る</li>
 *   <li>AC-4 サポーターでも所属 team/org 投稿が出る（メンバーと完全同一）</li>
 *   <li>AC-5 非所属スコープ（別 team / 別 org / 他人 PERSONAL / PUBLIC）は出ない</li>
 *   <li>AC-6 parentId≠null（返信）は出ない</li>
 *   <li>AC-7 非 PUBLISHED は出ない</li>
 *   <li>AC-8 新しい順（id 降順）</li>
 *   <li>AC-9 カーソル: limit 件返却で hasNext=true・nextCursor で続き取得（重複/欠落なし）</li>
 *   <li>AC-10 無所属ユーザーは空配列 + hasNext=false（500 / IN() エラーにしない）</li>
 *   <li>AC-11 自分の投稿が含まれる（仕様 a）</li>
 *   <li>AC-12 VILLAGE は出ない（仕様 b）</li>
 *   <li>pinned は常に空（仕様 c）</li>
 * </ul>
 */
@DisplayName("TimelineFeedController#getMyFeed 個人集約タイムライン統合テスト")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineMyFeedControllerIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private TimelineFeedController controller;

    @Autowired
    private TimelinePostRepository postRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Autowired
    private UserMuteRepository muteRepository;

    /** 組織 slug の一意性確保用の連番（slug は 30 文字・UNIQUE）。 */
    private int orgSeq = 0;

    // --- テスト用ユーザー（seed と衝突しない大きな ID） ---
    private static final Long USER_MEMBER = 92_001L;
    private static final Long USER_SUPPORTER = 92_002L;
    private static final Long USER_NONE = 92_003L;
    private static final Long OTHER_AUTHOR = 92_009L;

    // --- 所属スコープ ---
    private static final Long TEAM_JOINED = 70_001L;
    private static final Long ORG_JOINED = 80_001L;
    // --- 非所属スコープ ---
    private static final Long TEAM_OTHER = 70_002L;
    private static final Long ORG_OTHER = 80_002L;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        membershipRepository.deleteAll();
        muteRepository.deleteAll();

        // USER_MEMBER: 所属チーム/組織に MEMBER として在籍
        saveMembership(USER_MEMBER, ScopeType.TEAM, TEAM_JOINED, RoleKind.MEMBER);
        saveMembership(USER_MEMBER, ScopeType.ORGANIZATION, ORG_JOINED, RoleKind.MEMBER);
        // USER_SUPPORTER: 同じ所属チーム/組織に SUPPORTER として在籍
        saveMembership(USER_SUPPORTER, ScopeType.TEAM, TEAM_JOINED, RoleKind.SUPPORTER);
        saveMembership(USER_SUPPORTER, ScopeType.ORGANIZATION, ORG_JOINED, RoleKind.SUPPORTER);

        setAuthentication(USER_MEMBER);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-1 未認証 → 401
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-1 未認証: SecurityContext 無しで /my → BusinessException(COMMON_000 → 401)")
    void unauthenticated_throws() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> controller.getMyFeed(null, 20))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-2 / AC-3 メンバーの所属チーム・組織投稿が出る
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-2/AC-3 メンバー: 所属チーム投稿・所属組織投稿の両方が出る")
    void member_seesJoinedTeamAndOrgPosts() {
        Long teamPostId = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long orgPostId = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_MEMBER);
        List<PostResponse> posts = body(controller.getMyFeed(null, 20)).getData().getPosts();

        assertThat(posts).extracting(PostResponse::getId)
                .contains(teamPostId, orgPostId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-4 サポーターでもメンバーと完全同一の投稿が出る
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-4 サポーター: 所属チーム/組織投稿がメンバーと同様に出る")
    void supporter_seesSameJoinedScopePosts() {
        Long teamPostId = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long orgPostId = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_SUPPORTER);
        List<PostResponse> posts = body(controller.getMyFeed(null, 20)).getData().getPosts();

        assertThat(posts).extracting(PostResponse::getId)
                .contains(teamPostId, orgPostId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-5 非所属スコープは出ない
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5 非所属: 別チーム/別組織/他人PERSONAL/PUBLIC は出ない")
    void nonJoinedScopes_excluded() {
        Long visibleTeam = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long otherTeam = savePost(PostScopeType.TEAM, TEAM_OTHER, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long otherOrg = savePost(PostScopeType.ORGANIZATION, ORG_OTHER, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long personal = savePost(PostScopeType.PERSONAL, OTHER_AUTHOR, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long publicPost = savePost(PostScopeType.PUBLIC, 0L, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 50)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(visibleTeam);
        assertThat(ids).doesNotContain(otherTeam, otherOrg, personal, publicPost);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-6 返信（parentId≠null）は出ない
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6 返信: parentId≠null は出ない")
    void replies_excluded() {
        Long root = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long reply = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, root).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 50)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(root);
        assertThat(ids).doesNotContain(reply);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-7 非 PUBLISHED は出ない
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-7 非公開: DRAFT/HIDDEN/SCHEDULED は出ない")
    void nonPublished_excluded() {
        Long published = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long draft = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.DRAFT, null).getId();
        Long hidden = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.HIDDEN, null).getId();
        Long scheduled = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.SCHEDULED, null).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 50)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(published);
        assertThat(ids).doesNotContain(draft, hidden, scheduled);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-8 新しい順（id 降順）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-8 新しい順: id 降順で返る")
    void orderedByIdDesc() {
        Long first = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long second = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long third = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 50)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        // third > second > first（id 降順）
        assertThat(ids).containsExactly(third, second, first);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-9 カーソルページネーション（重複/欠落なし）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-9 カーソル: limit=2 で hasNext=true・nextCursor で続き取得（重複/欠落なし・終端は部分ページで hasNext=false）")
    void cursorPagination() {
        // 3 件 / limit 2: 終端ページが部分ページ（1 件 < limit）になり hasNext=false を明確に検証する。
        Long p1 = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long p2 = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long p3 = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_MEMBER);

        // 1 ページ目（最新 2 件・id 降順）
        TimelineFeedResponse page1 = body(controller.getMyFeed(null, 2));
        List<Long> ids1 = page1.getData().getPosts().stream().map(PostResponse::getId).toList();
        assertThat(ids1).containsExactly(p3, p2);
        assertThat(page1.getMeta().isHasNext()).isTrue();
        assertThat(page1.getMeta().getNextCursor()).isEqualTo(p2);

        // 2 ページ目（nextCursor で続き）。残り 1 件 < limit のため hasNext=false・nextCursor=null
        TimelineFeedResponse page2 = body(controller.getMyFeed(page1.getMeta().getNextCursor(), 2));
        List<Long> ids2 = page2.getData().getPosts().stream().map(PostResponse::getId).toList();
        assertThat(ids2).containsExactly(p1);
        // 重複なし・欠落なし（全 3 件を 2 ページで網羅）
        assertThat(ids2).doesNotContainAnyElementsOf(ids1);
        assertThat(page2.getMeta().isHasNext()).isFalse();
        assertThat(page2.getMeta().getNextCursor()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-10 無所属ユーザーは空配列 + hasNext=false
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-10 無所属: 空配列 + hasNext=false（500/IN()エラーにしない）")
    void noMembership_returnsEmpty() {
        savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null);
        savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null);

        setAuthentication(USER_NONE);
        TimelineFeedResponse resp = body(controller.getMyFeed(null, 20));

        assertThat(resp.getData().getPosts()).isEmpty();
        assertThat(resp.getMeta().isHasNext()).isFalse();
        assertThat(resp.getMeta().getNextCursor()).isNull();
    }

    @Test
    @DisplayName("AC-10 片方空（チームのみ所属）: 組織所属が無くても IN() エラーにならず取得できる")
    void onlyTeamMembership_noInError() {
        // 専用ユーザー: チームのみ所属（組織所属なし）
        Long teamOnlyUser = 92_004L;
        saveMembership(teamOnlyUser, ScopeType.TEAM, TEAM_JOINED, RoleKind.MEMBER);
        Long teamPost = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        Long orgPost = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();

        setAuthentication(teamOnlyUser);
        List<Long> ids = body(controller.getMyFeed(null, 20)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(teamPost);
        assertThat(ids).doesNotContain(orgPost);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-11 自分の投稿が含まれる
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-11 自分の投稿: 所属スコープへの自投稿も含まれる")
    void ownPost_included() {
        Long ownPost = savePost(PostScopeType.TEAM, TEAM_JOINED, USER_MEMBER, PostStatus.PUBLISHED, null).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 20)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(ownPost);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AC-12 VILLAGE は出ない
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-12 VILLAGE: 村スコープ投稿は集約対象外")
    void villagePosts_excluded() {
        Long teamPost = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR, PostStatus.PUBLISHED, null).getId();
        // VILLAGE 投稿（scopeId=0・scope_village_id を保持）
        TimelinePostEntity village = TimelinePostEntity.builder()
                .scopeType(PostScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(UUID.randomUUID())
                .userId(OTHER_AUTHOR)
                .content("village post")
                .status(PostStatus.PUBLISHED)
                .build();
        Long villageId = postRepository.save(village).getId();

        setAuthentication(USER_MEMBER);
        List<Long> ids = body(controller.getMyFeed(null, 50)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();

        assertThat(ids).contains(teamPost);
        assertThat(ids).doesNotContain(villageId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 仕様 c: pinned は常に空
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("仕様c pinned空: /my では pinned を出さない（pinned 投稿も posts 側に時系列で混在）")
    void pinnedAlwaysEmpty() {
        // ピン留め投稿を作っても /my では pinned=[] となり posts に混在する
        TimelinePostEntity pinnedPost = TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(TEAM_JOINED)
                .userId(OTHER_AUTHOR)
                .content("pinned post")
                .status(PostStatus.PUBLISHED)
                .isPinned(true)
                .build();
        Long pinnedId = postRepository.save(pinnedPost).getId();

        setAuthentication(USER_MEMBER);
        TimelineFeedResponse resp = body(controller.getMyFeed(null, 20));

        assertThat(resp.getData().getPinned()).isEmpty();
        assertThat(resp.getData().getPosts()).extracting(PostResponse::getId).contains(pinnedId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 配下配信（delivery_scope）— AC-1〜12
    //
    // 用語: 「距離」は閲覧者の起点組織から投稿元組織まで親方向に何ホップかを指す。
    //   DIRECT      … 距離 0（直接所属）のみ
    //   CHILDREN    … 距離 1 まで
    //   DESCENDANTS … 距離 1 以上すべて（app.org.max-depth まで）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("配下配信（delivery_scope）")
    class DeliveryScopeTest {

        @Test
        @DisplayName("配AC-1 DESCENDANTS: 親組織の投稿が子組織所属者のフィードに出る")
        void descendants_reachesChildOrgMember() {
            Long parent = saveOrg(null);
            Long child = saveOrg(parent);
            Long user = 93_101L;
            saveMembership(user, ScopeType.ORGANIZATION, child, RoleKind.MEMBER);

            Long postId = saveOrgPost(parent, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(user)).contains(postId);
        }

        @Test
        @DisplayName("配AC-2 DESCENDANTS: 孫組織（2段下）の所属者のフィードにも出る")
        void descendants_reachesGrandChildOrgMember() {
            Long root = saveOrg(null);
            Long child = saveOrg(root);
            Long grandChild = saveOrg(child);
            Long user = 93_102L;
            saveMembership(user, ScopeType.ORGANIZATION, grandChild, RoleKind.MEMBER);

            Long postId = saveOrgPost(root, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(user)).contains(postId);
        }

        @Test
        @DisplayName("配AC-3 DESCENDANTS: 組織 membership を持たずチームにのみ所属するユーザーにも届く")
        void descendants_reachesTeamOnlyMemberViaAnchorOrg() {
            Long root = saveOrg(null);
            Long child = saveOrg(root);
            Long teamId = 71_103L;
            saveTeamOrgMembership(teamId, child);
            Long user = 93_103L;
            // 組織 membership は持たない（チーム加入では自動生成されない）
            saveMembership(user, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            Long postId = saveOrgPost(root, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(user)).contains(postId);
        }

        @Test
        @DisplayName("配AC-4 陰性対照 DIRECT（既定）: 親組織の投稿は子組織所属者に出ない")
        void direct_doesNotReachChildOrgMember() {
            Long parent = saveOrg(null);
            Long child = saveOrg(parent);
            Long user = 93_104L;
            saveMembership(user, ScopeType.ORGANIZATION, child, RoleKind.MEMBER);

            Long postId = saveOrgPost(parent, PostDeliveryScope.DIRECT);

            assertThat(feedIds(user)).doesNotContain(postId);
        }

        @Test
        @DisplayName("配AC-5 CHILDREN: 直下の子組織には出るが孫組織には出ない（3択の中間値）")
        void children_reachesOnlyDirectChildren() {
            Long root = saveOrg(null);
            Long child = saveOrg(root);
            Long grandChild = saveOrg(child);
            Long childUser = 93_105L;
            Long grandChildUser = 93_106L;
            saveMembership(childUser, ScopeType.ORGANIZATION, child, RoleKind.MEMBER);
            saveMembership(grandChildUser, ScopeType.ORGANIZATION, grandChild, RoleKind.MEMBER);

            Long postId = saveOrgPost(root, PostDeliveryScope.CHILDREN);

            assertThat(feedIds(childUser)).contains(postId);
            assertThat(feedIds(grandChildUser)).doesNotContain(postId);
        }

        @Test
        @DisplayName("配AC-6 deliveryScope 省略時の保存値は DIRECT")
        void defaultDeliveryScope_isDirect() {
            Long org = saveOrg(null);
            TimelinePostEntity saved = postRepository.save(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.ORGANIZATION)
                    .scopeId(org)
                    .userId(OTHER_AUTHOR)
                    .content("delivery-default")
                    .status(PostStatus.PUBLISHED)
                    .build());

            assertThat(saved.getDeliveryScope()).isEqualTo(PostDeliveryScope.DIRECT);
        }

        @Test
        @DisplayName("配AC-7 陽性対照: DIRECT/CHILDREN/DESCENDANTS いずれも直接所属者には出る（非退行）")
        void allScopes_reachDirectMember() {
            Long org = saveOrg(null);
            Long user = 93_107L;
            saveMembership(user, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);

            Long direct = saveOrgPost(org, PostDeliveryScope.DIRECT);
            Long children = saveOrgPost(org, PostDeliveryScope.CHILDREN);
            Long descendants = saveOrgPost(org, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(user)).contains(direct, children, descendants);
        }

        @Test
        @DisplayName("配AC-8 無関係な組織の所属者には DESCENDANTS でも出ない")
        void descendants_doesNotReachUnrelatedOrgMember() {
            Long root = saveOrg(null);
            Long unrelated = saveOrg(null);
            Long user = 93_108L;
            saveMembership(user, ScopeType.ORGANIZATION, unrelated, RoleKind.MEMBER);

            Long postId = saveOrgPost(root, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(user)).doesNotContain(postId);
        }

        @Test
        @DisplayName("配AC-9 上方向には流れない: 子組織の DESCENDANTS 投稿は親組織所属者に出ない")
        void descendants_doesNotFlowUpwards() {
            Long parent = saveOrg(null);
            Long child = saveOrg(parent);
            Long parentUser = 93_109L;
            saveMembership(parentUser, ScopeType.ORGANIZATION, parent, RoleKind.MEMBER);

            Long postId = saveOrgPost(child, PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(parentUser)).doesNotContain(postId);
        }

        @Test
        @DisplayName("配AC-10 TEAM 投稿に DESCENDANTS を指定しても配信範囲は変わらない")
        void teamPost_deliveryScopeHasNoEffect() {
            Long teamId = 71_110L;
            Long org = saveOrg(null);
            saveTeamOrgMembership(teamId, org);
            Long outsider = 93_110L;
            // チーム非所属だが同じアンカー組織に所属している（もし TEAM に配下配信が効くと誤って届く）
            saveMembership(outsider, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);

            TimelinePostEntity post = postRepository.save(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(teamId)
                    .userId(OTHER_AUTHOR)
                    .content("team-descendants")
                    .status(PostStatus.PUBLISHED)
                    .deliveryScope(PostDeliveryScope.DESCENDANTS)
                    .build());

            assertThat(feedIds(outsider)).doesNotContain(post.getId());
        }

        @Test
        @DisplayName("配AC-11 app.org.max-depth（既定5）を超える深さの子孫には届かない")
        void descendants_stopsAtMaxDepth() {
            // O0(root) → O1 → ... → O6 の 7 段。O5 は距離 5（届く）、O6 は距離 6（届かない）。
            Long[] chain = new Long[7];
            chain[0] = saveOrg(null);
            for (int i = 1; i < chain.length; i++) {
                chain[i] = saveOrg(chain[i - 1]);
            }
            Long withinDepth = 93_111L;
            Long beyondDepth = 93_112L;
            saveMembership(withinDepth, ScopeType.ORGANIZATION, chain[5], RoleKind.MEMBER);
            saveMembership(beyondDepth, ScopeType.ORGANIZATION, chain[6], RoleKind.MEMBER);

            Long postId = saveOrgPost(chain[0], PostDeliveryScope.DESCENDANTS);

            assertThat(feedIds(withinDepth)).contains(postId);
            assertThat(feedIds(beyondDepth)).doesNotContain(postId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ミュート結線 — AC-19〜24, 27〜29
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ミュート結線")
    class MuteTest {

        @Test
        @DisplayName("ミAC-19/20 直接所属チーム T をミュートすると出ない・解除で再び出る")
        void muteTeam_hidesAndUnhides() {
            Long postId = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();

            saveMute(USER_MEMBER, "TEAM", TEAM_JOINED);
            assertThat(feedIds(USER_MEMBER)).doesNotContain(postId);

            muteRepository.deleteAll(muteRepository.findByUserId(USER_MEMBER));
            assertThat(feedIds(USER_MEMBER)).contains(postId);
        }

        @Test
        @DisplayName("ミAC-21 陽性対照: 組織 X のミュートは非ミュートのチーム T・組織 Y に影響しない")
        void muteOrg_doesNotAffectOtherScopes() {
            Long orgY = saveOrg(null);
            saveMembership(USER_MEMBER, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);
            Long mutedOrgPost = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();
            Long teamPost = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();
            Long orgYPost = savePost(PostScopeType.ORGANIZATION, orgY, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();

            saveMute(USER_MEMBER, "ORGANIZATION", ORG_JOINED);

            List<Long> ids = feedIds(USER_MEMBER);
            assertThat(ids).doesNotContain(mutedOrgPost);
            assertThat(ids).contains(teamPost, orgYPost);
        }

        @Test
        @DisplayName("ミAC-22 上位組織 X の配下配信投稿は「X をミュート」で止まる（対象＝投稿元スコープ）")
        void muteDeliveringOrg_stopsDeliveredPost() {
            Long parent = saveOrg(null);
            Long child = saveOrg(parent);
            Long user = 93_201L;
            saveMembership(user, ScopeType.ORGANIZATION, child, RoleKind.MEMBER);
            Long postId = saveOrgPost(parent, PostDeliveryScope.DESCENDANTS);

            // ミュート前は届いている（陽性対照）
            assertThat(feedIds(user)).contains(postId);

            saveMute(user, "ORGANIZATION", parent);
            assertThat(feedIds(user)).doesNotContain(postId);
        }

        @Test
        @DisplayName("ミAC-23 陰性対照: 所属チーム T のミュートでは上位組織 X の配下配信は止まらない")
        void muteTeam_doesNotStopOrgDelivery() {
            Long parent = saveOrg(null);
            Long anchor = saveOrg(parent);
            Long teamId = 71_202L;
            saveTeamOrgMembership(teamId, anchor);
            Long user = 93_202L;
            saveMembership(user, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            Long postId = saveOrgPost(parent, PostDeliveryScope.DESCENDANTS);

            saveMute(user, "TEAM", teamId);

            assertThat(feedIds(user)).contains(postId);
        }

        @Test
        @DisplayName("ミAC-24 ページング: ミュート混在でも 1 ページ目・2 ページ目とも limit 件ちょうど返る")
        void mutedPosts_doNotCauseSparsePaging() {
            Long mutedTeam = 71_203L;
            saveMembership(USER_MEMBER, ScopeType.TEAM, mutedTeam, RoleKind.MEMBER);

            // 可視 45 件・ミュート対象 45 件を交互に作る（アプリ側フィルタなら 1 ページ目が半減する）
            List<Long> visibleIds = new java.util.ArrayList<>();
            for (int i = 0; i < 45; i++) {
                visibleIds.add(savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR,
                        PostStatus.PUBLISHED, null).getId());
                savePost(PostScopeType.TEAM, mutedTeam, OTHER_AUTHOR, PostStatus.PUBLISHED, null);
            }
            saveMute(USER_MEMBER, "TEAM", mutedTeam);

            setAuthentication(USER_MEMBER);
            TimelineFeedResponse page1 = body(controller.getMyFeed(null, 20));
            List<Long> ids1 = page1.getData().getPosts().stream().map(PostResponse::getId).toList();
            assertThat(ids1).hasSize(20);
            assertThat(ids1).allMatch(visibleIds::contains);
            assertThat(page1.getMeta().isHasNext()).isTrue();

            TimelineFeedResponse page2 = body(controller.getMyFeed(page1.getMeta().getNextCursor(), 20));
            List<Long> ids2 = page2.getData().getPosts().stream().map(PostResponse::getId).toList();
            assertThat(ids2).hasSize(20);
            assertThat(ids2).allMatch(visibleIds::contains);
            assertThat(ids2).doesNotContainAnyElementsOf(ids1);
        }

        @Test
        @DisplayName("ミAC-28 非退行: ミュート 0 件のユーザーは所属スコープの投稿を全件見られる")
        void noMutes_seesEverything() {
            Long teamPost = savePost(PostScopeType.TEAM, TEAM_JOINED, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();
            Long orgPost = savePost(PostScopeType.ORGANIZATION, ORG_JOINED, OTHER_AUTHOR,
                    PostStatus.PUBLISHED, null).getId();

            assertThat(muteRepository.findByUserId(USER_MEMBER)).isEmpty();
            assertThat(feedIds(USER_MEMBER)).contains(teamPost, orgPost);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────────

    /** 認証を差し替えてマイフィードの投稿 ID 一覧（十分な件数）を取る。 */
    private List<Long> feedIds(Long userId) {
        setAuthentication(userId);
        return body(controller.getMyFeed(null, 100)).getData().getPosts()
                .stream().map(PostResponse::getId).toList();
    }

    /** 組織を 1 件作る（親 ID を指定すると子組織になる）。ID は採番されるため戻り値を使う。 */
    private Long saveOrg(Long parentOrgId) {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("dlv" + (orgSeq++) + "-" + (System.nanoTime() % 1_000_000L))
                .name("配下配信テスト組織")
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrgId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(false)
                .build());
        return org.getId();
    }

    /** チームのアンカー組織（ACTIVE な team_org_memberships）を作る。 */
    private void saveTeamOrgMembership(Long teamId, Long organizationId) {
        teamOrgMembershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.now())
                .build());
    }

    /** 組織スコープ投稿を配信範囲付きで作る。 */
    private Long saveOrgPost(Long orgId, PostDeliveryScope deliveryScope) {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.ORGANIZATION)
                .scopeId(orgId)
                .userId(OTHER_AUTHOR)
                .content("org-post-" + orgId + "-" + deliveryScope)
                .status(PostStatus.PUBLISHED)
                .deliveryScope(deliveryScope)
                .build()).getId();
    }

    private void saveMute(Long userId, String mutedType, Long mutedId) {
        muteRepository.save(UserMuteEntity.builder()
                .userId(userId)
                .mutedType(mutedType)
                .mutedId(mutedId)
                .build());
    }

    private TimelinePostEntity savePost(
            PostScopeType scopeType, Long scopeId, Long userId, PostStatus status, Long parentId) {
        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(userId)
                .content("post-" + scopeType + "-" + scopeId)
                .status(status)
                .parentId(parentId)
                .build();
        return postRepository.save(post);
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private TimelineFeedResponse body(ResponseEntity<TimelineFeedResponse> resp) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
    }
}
