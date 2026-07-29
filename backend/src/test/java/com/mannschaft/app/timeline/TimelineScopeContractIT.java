package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.controller.TimelineAttachmentController;
import com.mannschaft.app.timeline.controller.TimelineBookmarkController;
import com.mannschaft.app.timeline.controller.TimelinePollController;
import com.mannschaft.app.timeline.controller.TimelinePostController;
import com.mannschaft.app.timeline.controller.TimelineReactionController;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.dto.ImageUploadUrlRequest;
import com.mannschaft.app.timeline.dto.ImageUploadUrlResponse;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.dto.PollVoteRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
import com.mannschaft.app.timeline.entity.TimelinePollEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePollOptionRepository;
import com.mannschaft.app.timeline.repository.TimelinePollRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 認可根治戦役 Wave7（timeline）: 投稿管理（更新・削除・ピン留め）・投票・みたよ！・ブックマーク・
 * 添付Presigned URL発行の認可契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave7 timeline節）。金型: {@link TimelineWriteScopeContractIT}
 * （Controller 直接 Autowire + SecurityContext 差し替え + JPA リポジトリ直接 save でのシード方式）。</p>
 *
 * <h3>対象EP</h3>
 * <ul>
 *   <li>{@code TimelinePostController#updatePost/deletePost/togglePin}
 *       — 投稿者本人 or TEAM スコープの ADMIN+（{@code TimelinePostAccessGuard}）</li>
 *   <li>{@code TimelinePollController#getPoll/vote}
 *       — 投稿本体と同一の可視性判定（{@code TimelinePostVisibilityAccessGuard}）</li>
 *   <li>{@code TimelineReactionController#addReaction/removeReaction} — 同上</li>
 *   <li>{@code TimelineBookmarkController#addBookmark} — 同上</li>
 *   <li>{@code TimelineAttachmentController#getImageUploadUrl/getVideoUploadUrl}
 *       — アップロード先スコープのメンバーシップ（{@code TimelineAttachmentAccessGuard}）</li>
 * </ul>
 *
 * <p>R2StorageService は外部依存のため {@code @MockitoBean} でモックする（presign URL 発行）。
 * StorageQuotaService も同様にモックする（本 IT の対象は認可ゲートであり、既存のクォータ機構の
 * 挙動検証は対象外のため）。</p>
 */
@DisplayName("timeline 投稿管理/投票/みたよ！/ブックマーク/添付Presigned 認可契約テスト（認可根治 Wave7）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TimelinePostController postController;

    @Autowired
    private TimelinePollController pollController;

    @Autowired
    private TimelineBookmarkController bookmarkController;

    @Autowired
    private TimelineReactionController reactionController;

    @Autowired
    private TimelineAttachmentController attachmentController;

    @Autowired
    private TimelinePostRepository postRepository;

    @Autowired
    private TimelinePollRepository pollRepository;

    @Autowired
    private TimelinePollOptionRepository pollOptionRepository;

    @PersistenceContext
    private EntityManager em;

    /** R2 は外部依存のため mock（presign URL 発行）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    /** 本 IT の対象は認可ゲートであり、クォータ機構自体の挙動検証は対象外のため mock（既定は許可）。 */
    @MockitoBean
    private com.mannschaft.app.common.storage.quota.StorageQuotaService storageQuotaService;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    private static final Long USER_OWNER = 93_101L;
    private static final Long USER_TEAM_A_ADMIN = 93_102L;
    private static final Long USER_TEAM_A_MEMBER = 93_103L;
    private static final Long USER_TEAM_B_ADMIN = 93_104L;
    private static final Long USER_OUTSIDER = 93_105L;

    // --- 所属スコープ ---
    private static final Long TEAM_A = 71_101L;
    private static final Long TEAM_B = 71_102L;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertMembership(em, USER_OWNER, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, USER_TEAM_A_ADMIN, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, USER_TEAM_A_ADMIN, "ADMIN", TEAM_A, null);
        MembershipTestHelper.insertMembership(em, USER_TEAM_A_MEMBER, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, USER_TEAM_B_ADMIN, ScopeType.TEAM, TEAM_B, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, USER_TEAM_B_ADMIN, "ADMIN", TEAM_B, null);
        em.flush();

        given(r2StorageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    return new PresignedUploadResult("https://example.com/upload", key, 900L);
                });
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** TEAM_A スコープの投稿を USER_OWNER 名義で作る。 */
    private TimelinePostEntity teamAPost() {
        return postRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(TEAM_A)
                .userId(USER_OWNER)
                .postedAsType(PostedAsType.USER)
                .content("チームAの投稿")
                .status(PostStatus.PUBLISHED)
                .build());
    }

    /** 投稿に投票（選択肢1件）を作り、選択肢 ID を返す。 */
    private Long createPollFor(Long postId) {
        TimelinePollEntity poll = pollRepository.save(TimelinePollEntity.builder()
                .timelinePostId(postId)
                .question("好きな色は？")
                .build());
        TimelinePollOptionEntity option = pollOptionRepository.save(TimelinePollOptionEntity.builder()
                .timelinePollId(poll.getId())
                .optionText("赤")
                .build());
        return option.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 投稿管理（更新・削除・ピン留め）: 投稿者本人 or TEAM ADMIN+
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("updatePost/deletePost/togglePin: 投稿者本人 or ADMIN+")
    class PostManagement {

        @Test
        @DisplayName("非ADMINメンバーは他人の投稿を更新できない（NOT_POST_OWNER）")
        void 非ADMINメンバーは更新できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            assertThatThrownBy(() -> postController.updatePost(postId, new UpdatePostRequest("改ざん")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("別スコープ(TEAM_B)のADMINは他人の投稿を更新できない（BOLA）")
        void 別スコープADMINは更新できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_B_ADMIN);

            assertThatThrownBy(() -> postController.updatePost(postId, new UpdatePostRequest("改ざん")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("投稿者本人は自分の投稿を更新できる")
        void 本人は更新できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OWNER);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.updatePost(postId, new UpdatePostRequest("本人による更新"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postRepository.findById(postId).orElseThrow().getContent())
                    .isEqualTo("本人による更新");
        }

        @Test
        @DisplayName("同スコープ(TEAM_A)のADMINは他人の投稿を更新できる")
        void 同スコープADMINは更新できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_ADMIN);

            ResponseEntity<ApiResponse<PostResponse>> response =
                    postController.updatePost(postId, new UpdatePostRequest("ADMINによる更新"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postRepository.findById(postId).orElseThrow().getContent())
                    .isEqualTo("ADMINによる更新");
        }

        @Test
        @DisplayName("非ADMINメンバーは他人の投稿を削除できない（NOT_POST_OWNER）")
        void 非ADMINメンバーは削除できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            assertThatThrownBy(() -> postController.deletePost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("別スコープ(TEAM_B)のADMINは他人の投稿を削除できない（BOLA）")
        void 別スコープADMINは削除できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_B_ADMIN);

            assertThatThrownBy(() -> postController.deletePost(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("投稿者本人は自分の投稿を削除できる")
        void 本人は削除できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OWNER);

            ResponseEntity<Void> response = postController.deletePost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // deletePost は論理削除（TimelinePostEntity#softDelete）。同一トランザクション内では
            // 永続化コンテキストの 1st level cache により findById が管理中の同一インスタンスを
            // 返す（@SQLRestriction はキャッシュヒット時には評価されず、新規 SELECT 発行時のみ効く）
            // ため、削除の成否は再取得の有無ではなく softDelete が実際に適用された状態で検証する。
            TimelinePostEntity deleted = postRepository.findById(postId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED);
        }

        @Test
        @DisplayName("同スコープ(TEAM_A)のADMINは他人の投稿を削除できる")
        void 同スコープADMINは削除できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_ADMIN);

            ResponseEntity<Void> response = postController.deletePost(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // 論理削除の検証方針は上記「本人は削除できる」と同一（1st level cache の注記参照）。
            TimelinePostEntity deleted = postRepository.findById(postId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED);
        }

        @Test
        @DisplayName("非ADMINメンバーは他人の投稿のピン留めを変更できない（NOT_POST_OWNER）")
        void 非ADMINメンバーはピン留め変更できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            assertThatThrownBy(() -> postController.togglePin(postId, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("投稿者本人は自分の投稿のピン留めを変更できる")
        void 本人はピン留め変更できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OWNER);

            ResponseEntity<ApiResponse<PostResponse>> response = postController.togglePin(postId, true);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postRepository.findById(postId).orElseThrow().getIsPinned()).isTrue();
        }

        @Test
        @DisplayName("同スコープ(TEAM_A)のADMINは他人の投稿のピン留めを変更できる")
        void 同スコープADMINはピン留め変更できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_ADMIN);

            ResponseEntity<ApiResponse<PostResponse>> response = postController.togglePin(postId, true);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postRepository.findById(postId).orElseThrow().getIsPinned()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 投票（投稿本体と同一の可視性判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPoll/vote: 投稿本体と同一の可視性判定")
    class Poll {

        @Test
        @DisplayName("非メンバーは投票結果を取得できない（POST_NOT_FOUND）")
        void 非メンバーは投票結果を取得できない() {
            Long postId = teamAPost().getId();
            createPollFor(postId);
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> pollController.getPoll(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("同スコープのメンバーは投票結果を取得できる")
        void メンバーは投票結果を取得できる() {
            Long postId = teamAPost().getId();
            createPollFor(postId);
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<PollResponse>> response = pollController.getPoll(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getQuestion()).isEqualTo("好きな色は？");
        }

        @Test
        @DisplayName("非メンバーは投票できない（POST_NOT_FOUND・投票数は増えない）")
        void 非メンバーは投票できない() {
            Long postId = teamAPost().getId();
            Long optionId = createPollFor(postId);
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> pollController.vote(postId, new PollVoteRequest(optionId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(pollOptionRepository.findById(optionId).orElseThrow().getVoteCount()).isZero();
        }

        @Test
        @DisplayName("同スコープのメンバーは投票できる")
        void メンバーは投票できる() {
            Long postId = teamAPost().getId();
            Long optionId = createPollFor(postId);
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<PollResponse>> response =
                    pollController.vote(postId, new PollVoteRequest(optionId));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(pollOptionRepository.findById(optionId).orElseThrow().getVoteCount()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // みたよ！（投稿本体と同一の可視性判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("addReaction/removeReaction: 投稿本体と同一の可視性判定")
    class Reaction {

        @Test
        @DisplayName("非メンバーはみたよ！を追加できない（POST_NOT_FOUND・カウントも増えない）")
        void 非メンバーは追加できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> reactionController.addReaction(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            assertThat(postRepository.findById(postId).orElseThrow().getReactionCount()).isZero();
        }

        @Test
        @DisplayName("同スコープのメンバーはみたよ！を追加・削除できる")
        void メンバーは追加削除できる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<ReactionResponse>> added = reactionController.addReaction(postId);
            assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(postRepository.findById(postId).orElseThrow().getReactionCount()).isEqualTo(1);

            ResponseEntity<ApiResponse<ReactionResponse>> removed = reactionController.removeReaction(postId);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(postRepository.findById(postId).orElseThrow().getReactionCount()).isZero();
        }

        @Test
        @DisplayName("非メンバーはみたよ！を削除できない（POST_NOT_FOUND）")
        void 非メンバーは削除できない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> reactionController.removeReaction(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ブックマーク（投稿本体と同一の可視性判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("addBookmark: 投稿本体と同一の可視性判定")
    class Bookmark {

        @Test
        @DisplayName("非メンバーはブックマークできない（POST_NOT_FOUND・存在オラクル対策）")
        void 非メンバーはブックマークできない() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_OUTSIDER);

            assertThatThrownBy(() -> bookmarkController.addBookmark(postId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("同スコープのメンバーはブックマークできる")
        void メンバーはブックマークできる() {
            Long postId = teamAPost().getId();
            setAuthentication(USER_TEAM_A_MEMBER);

            ResponseEntity<ApiResponse<BookmarkResponse>> response = bookmarkController.addBookmark(postId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getTimelinePostId()).isEqualTo(postId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 添付Presigned URL発行（アップロード先スコープのメンバーシップ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getImageUploadUrl/getVideoUploadUrl: アップロード先スコープのメンバーシップ")
    class AttachmentUpload {

        @Test
        @DisplayName("TEAMスコープの非メンバーは画像Presigned URLを取得できない（403）")
        void 非メンバーは画像URLを取得できない() {
            setAuthentication(USER_OUTSIDER);
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/jpeg", "TEAM", TEAM_A);

            assertThatThrownBy(() -> attachmentController.getImageUploadUrl(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("TEAMスコープのメンバーは画像Presigned URLを取得できる")
        void メンバーは画像URLを取得できる() {
            setAuthentication(USER_TEAM_A_MEMBER);
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/jpeg", "TEAM", TEAM_A);

            ResponseEntity<ApiResponse<ImageUploadUrlResponse>> response =
                    attachmentController.getImageUploadUrl(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getFileKey()).startsWith("timeline/TEAM/" + TEAM_A + "/");
        }

        @Test
        @DisplayName("TEAMスコープの非メンバーは動画Presigned URLを取得できない（403）")
        void 非メンバーは動画URLを取得できない() {
            setAuthentication(USER_OUTSIDER);
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/mp4", "TEAM", TEAM_A);

            assertThatThrownBy(() -> attachmentController.getVideoUploadUrl(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("TEAMスコープのメンバーは動画Presigned URLを取得できる")
        void メンバーは動画URLを取得できる() {
            setAuthentication(USER_TEAM_A_MEMBER);
            VideoUploadUrlRequest req = new VideoUploadUrlRequest("video/mp4", "TEAM", TEAM_A);

            ResponseEntity<ApiResponse<VideoUploadUrlResponse>> response =
                    attachmentController.getVideoUploadUrl(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getFileKey()).startsWith("timeline/TEAM/" + TEAM_A + "/");
        }

        @Test
        @DisplayName("PUBLICスコープは所属を問わず画像Presigned URLを取得できる（回帰防止）")
        void PUBLICスコープは誰でも画像URLを取得できる() {
            setAuthentication(USER_OUTSIDER);
            ImageUploadUrlRequest req = new ImageUploadUrlRequest("image/jpeg", "PUBLIC", null);

            ResponseEntity<ApiResponse<ImageUploadUrlResponse>> response =
                    attachmentController.getImageUploadUrl(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
