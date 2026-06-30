package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────────

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
