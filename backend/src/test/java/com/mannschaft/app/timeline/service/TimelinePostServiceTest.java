package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.AttachmentType;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.AttachmentResponse;
import com.mannschaft.app.timeline.dto.CreateAttachmentRequest;
import com.mannschaft.app.timeline.dto.CreatePollRequest;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEditEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostAttachmentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostEditRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelinePostService} の単体テスト。
 * 投稿CRUD・フィード取得・検索・ピン留めを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelinePostService 単体テスト")
class TimelinePostServiceTest {

    @Mock
    private TimelinePostRepository postRepository;

    @Mock
    private TimelinePostAttachmentRepository attachmentRepository;

    @Mock
    private TimelinePostEditRepository editRepository;

    @Mock
    private TimelinePostReactionRepository reactionRepository;

    @Mock
    private TimelinePollService pollService;

    @Mock
    private TimelineMapper timelineMapper;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private StorageQuotaService storageQuotaService;

    @Mock
    private PostingIdentityService postingIdentityService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private com.mannschaft.app.membership.service.MembershipService membershipService;

    @Mock
    private com.mannschaft.app.common.NameResolverService nameResolverService;

    @Mock
    private com.mannschaft.app.team.service.TeamService teamService;

    @Mock
    private com.mannschaft.app.organization.service.OrganizationService organizationService;

    @InjectMocks
    private TimelinePostService timelinePostService;

    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    private TimelinePostEntity createPost() {
        return TimelinePostEntity.builder()
                .scopeType(PostScopeType.PUBLIC)
                .scopeId(0L)
                .userId(USER_ID)
                .postedAsType(PostedAsType.USER)
                .content("テスト投稿")
                .status(PostStatus.PUBLISHED)
                .build();
    }

    private PostResponse createPostResponse() {
        return PostResponse.builder()
                .id(POST_ID)
                .scope(new PostResponse.PostScopeDto("PUBLIC", 0L))
                .author(new PostResponse.PostAuthorDto(USER_ID, null, "USER", null))
                .content(new PostResponse.PostContentDto("テスト投稿", null, null, "PUBLISHED", null, false))
                .stats(new PostResponse.PostStatsDto(0, 0, 0, (short) 0, (short) 0))
                .audit(new PostResponse.PostAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }

    /**
     * enrich 前の生 PostResponse（生 ID のみ・name/slug/user/postedAs 未設定）を作る共通ヘルパー。
     * getFeed / getPinnedPosts / getReplies / getPostDetail#recentReplies の enrich 検証で使う。
     */
    private PostResponse rawFeedPost(long id, String scopeType, Long scopeId,
                                     Long userId, String postedAsType, Long postedAsId) {
        return PostResponse.builder()
                .id(id)
                .scope(new PostResponse.PostScopeDto(scopeType, scopeId))
                .author(new PostResponse.PostAuthorDto(userId, null, postedAsType, postedAsId))
                .content(new PostResponse.PostContentDto("本文", null, null, "PUBLISHED", null, false))
                .stats(new PostResponse.PostStatsDto(0, 0, 0, (short) 0, (short) 0))
                .audit(new PostResponse.PostAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }

    /**
     * enrich が呼ぶ 8 種の名前/slug/アイコン解決をすべて空 Map で lenient スタブする。
     * lenient のため、各テストで一部を strict {@code given} で上書きしても
     * UnnecessaryStubbing にならない（上書きは最後の宣言が優先される）。
     */
    private void stubAllResolversEmpty() {
        org.mockito.Mockito.lenient().when(nameResolverService.resolveTeamNames(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(nameResolverService.resolveOrganizationNames(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(teamService.getSlugsByIds(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(organizationService.getSlugsByIds(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(nameResolverService.resolveTeamIconUrls(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(nameResolverService.resolveOrganizationIconUrls(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(nameResolverService.resolveUserDisplayNames(anySet())).thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(nameResolverService.resolveUserAvatarUrls(anySet())).thenReturn(Map.of());
    }

    // ========================================
    // createPost
    // ========================================
    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("正常系: テキスト投稿を作成できる")
        void テキスト投稿を作成できる() {
            // given
            CreatePostRequest req = new CreatePostRequest("テスト投稿", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: VILLAGEスコープ投稿はPostingIdentityServiceで検証される")
        void VILLAGEスコープ投稿はPostingIdentityServiceで検証される() {
            // given
            UUID villageId = UUID.randomUUID();
            Long orgSubjectId = 89L;
            // 完全コンストラクタ（12 引数・scopeId は String）で scopeVillageId まで指定
            CreatePostRequest req = new CreatePostRequest("村への告知", "VILLAGE", "0",
                    "ORGANIZATION", orgSubjectId, null, null, null, null, null, null, villageId);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then: PostingIdentityService が ORGANIZATION=89 で検証されること
            verify(postingIdentityService).validatePostingIdentity(
                    eq(USER_ID), eq(villageId), eq(VillageSubjectType.ORGANIZATION), eq(orgSubjectId));
        }

        @Test
        @DisplayName("正常系: 予約投稿の場合はSCHEDULEDステータスで作成される")
        void 予約投稿の場合はSCHEDULEDステータスで作成される() {
            // given
            LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1);
            CreatePostRequest req = new CreatePostRequest("予約投稿", "PUBLIC", 0L,
                    "USER", null, null, null, scheduledAt, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: リプライの場合は親投稿のリプライ数がインクリメントされる")
        void リプライの場合は親投稿のリプライ数がインクリメントされる() {
            // given
            Long parentId = 10L;
            CreatePostRequest req = new CreatePostRequest("リプライ", "PUBLIC", 0L,
                    "USER", null, parentId, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            TimelinePostEntity parent = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(postRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(postRepository).findById(parentId);
        }

        @Test
        @DisplayName("正常系[情報漏洩防止]: TEAMスコープ投稿へのリプライは親と同じTEAMスコープで作成される")
        void TEAMスコープ投稿へのリプライは親スコープを継承する() {
            // given
            Long parentId = 10L;
            Long teamId = 50L;
            // リクエストには scope 情報を入れない（FE は content と parentId のみ送る）
            CreatePostRequest req = new CreatePostRequest("チームへのリプライ", null, null,
                    "USER", null, parentId, null, null, null, null);
            // 親投稿は TEAM スコープ
            TimelinePostEntity parentPost = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(teamId)
                    .userId(USER_ID)
                    .postedAsType(PostedAsType.USER)
                    .content("チームの元投稿")
                    .status(PostStatus.PUBLISHED)
                    .build();
            TimelinePostEntity savedReply = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(teamId)
                    .userId(USER_ID)
                    .postedAsType(PostedAsType.USER)
                    .content("チームへのリプライ")
                    .parentId(parentId)
                    .status(PostStatus.PUBLISHED)
                    .build();
            PostResponse expected = createPostResponse();

            given(postRepository.findById(parentId)).willReturn(Optional.of(parentPost));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedReply);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then: save に渡されたエンティティのスコープが親と同じ TEAM/teamId であること
            // （リクエストの scopeType=null → PUBLIC デフォルトではなく、親の TEAM が継承される）
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            // 最初の save 呼び出し（リプライ投稿の保存）をキャプチャ
            verify(postRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
            TimelinePostEntity capturedReply = cap.getAllValues().stream()
                    .filter(e -> parentId.equals(e.getParentId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("リプライエンティティが save されていない"));
            assertThat(capturedReply.getScopeType())
                    .as("リプライのscopeTypeは親のTEAMを継承していること（PUBLIC化による情報漏洩がないこと）")
                    .isEqualTo(PostScopeType.TEAM);
            assertThat(capturedReply.getScopeId())
                    .as("リプライのscopeIdは親のteamIdを継承していること")
                    .isEqualTo(teamId);
        }

        @Test
        @DisplayName("正常系[情報漏洩防止]: ORGANIZATIONスコープ投稿へのリプライも親スコープを継承する")
        void ORGANIZATIONスコープ投稿へのリプライは親スコープを継承する() {
            // given
            Long parentId = 20L;
            Long orgId = 70L;
            CreatePostRequest req = new CreatePostRequest("組織へのリプライ", null, null,
                    "USER", null, parentId, null, null, null, null);
            TimelinePostEntity parentPost = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.ORGANIZATION)
                    .scopeId(orgId)
                    .userId(USER_ID)
                    .postedAsType(PostedAsType.USER)
                    .content("組織の元投稿")
                    .status(PostStatus.PUBLISHED)
                    .build();
            TimelinePostEntity savedReply = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.ORGANIZATION)
                    .scopeId(orgId)
                    .userId(USER_ID)
                    .postedAsType(PostedAsType.USER)
                    .content("組織へのリプライ")
                    .parentId(parentId)
                    .status(PostStatus.PUBLISHED)
                    .build();
            PostResponse expected = createPostResponse();

            given(postRepository.findById(parentId)).willReturn(Optional.of(parentPost));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedReply);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then: ORGANIZATION スコープが継承されること
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(postRepository, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
            TimelinePostEntity capturedReply = cap.getAllValues().stream()
                    .filter(e -> parentId.equals(e.getParentId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("リプライエンティティが save されていない"));
            assertThat(capturedReply.getScopeType())
                    .as("リプライのscopeTypeは親のORGANIZATIONを継承していること")
                    .isEqualTo(PostScopeType.ORGANIZATION);
            assertThat(capturedReply.getScopeId())
                    .as("リプライのscopeIdは親のorgIdを継承していること")
                    .isEqualTo(orgId);
        }

        @Test
        @DisplayName("異常系: 存在しない親投稿へのリプライはPOST_NOT_FOUNDエラー")
        void 存在しない親投稿へのリプライはエラー() {
            // given
            Long parentId = 999L;
            CreatePostRequest req = new CreatePostRequest("リプライ試み", null, null,
                    "USER", null, parentId, null, null, null, null);
            given(postRepository.findById(parentId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
            // 親が見つからない場合は投稿は保存されない
            then(postRepository).should(org.mockito.Mockito.never()).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: リポストの場合は元投稿のリポスト数がインクリメントされる")
        void リポストの場合は元投稿のリポスト数がインクリメントされる() {
            // given
            Long repostOfId = 20L;
            CreatePostRequest req = new CreatePostRequest(null, "PUBLIC", 0L,
                    "USER", null, null, repostOfId, null, null, null);
            TimelinePostEntity savedPost = createPost();
            TimelinePostEntity original = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(postRepository.findById(repostOfId)).willReturn(Optional.of(original));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(postRepository).findById(repostOfId);
        }

        @Test
        @DisplayName("正常系: 投票付き投稿を作成できる")
        void 投票付き投稿を作成できる() {
            // given
            CreatePollRequest pollReq = new CreatePollRequest("質問？", List.of("A", "B"), null);
            CreatePostRequest req = new CreatePostRequest("投票付き", "PUBLIC", 0L,
                    "USER", null, null, null, null, pollReq, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(pollService).createPoll(any(), eq(pollReq));
        }

        @Test
        @DisplayName("異常系: コンテンツが空でリポストでも投票でもない場合はエラー")
        void コンテンツが空でリポストでも投票でもない場合はエラー() {
            // given
            CreatePostRequest req = new CreatePostRequest("", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.EMPTY_POST_CONTENT));
        }

        @Test
        @DisplayName("異常系: 添付ファイルが10件を超過するとエラー")
        void 添付ファイルが10件を超過するとエラー() {
            // given
            List<CreateAttachmentRequest> attachments = java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> new CreateAttachmentRequest("IMAGE", "key" + i, "file" + i + ".jpg",
                            1024L, "image/jpeg", null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null))
                    .toList();
            CreatePostRequest req = new CreatePostRequest("テスト", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, attachments);

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED));
        }

        // F09.13 Phase 2-α-2: status 指定による DRAFT 起票
        @Test
        @DisplayName("正常系: status=DRAFT を指定するとDRAFTで保存される")
        void status_DRAFT指定でDRAFT保存() {
            // given
            CreatePostRequest req = new CreatePostRequest("下書き投稿", "TEAM", 50L,
                    "USER", null, null, null, /* scheduledAt */ null, null, null,
                    /* status */ PostStatus.DRAFT);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then: save に渡されたエンティティの status が DRAFT であること
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(postRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(PostStatus.DRAFT);

            // DRAFT 起票時は TimelinePostCreatedEvent が発行されないこと
            verify(domainEventPublisher, org.mockito.Mockito.never())
                    .publish(any(com.mannschaft.app.timeline.event.TimelinePostCreatedEvent.class));
        }

        @Test
        @DisplayName("正常系: status=DRAFT は scheduledAt より優先される")
        void status_DRAFTはscheduledAtより優先() {
            // given: scheduledAt があっても status=DRAFT を尊重する
            LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1);
            CreatePostRequest req = new CreatePostRequest("下書き優先", "TEAM", 50L,
                    "USER", null, null, null, scheduledAt, null, null, PostStatus.DRAFT);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(postRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(PostStatus.DRAFT);
        }

        @Test
        @DisplayName("正常系: status=null + scheduledAt なし は PUBLISHED で保存される（既存挙動）")
        void status_null_PUBLISHED維持() {
            // given
            CreatePostRequest req = new CreatePostRequest("通常投稿", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, null, /* status */ null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(postRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(PostStatus.PUBLISHED);
        }

        @Test
        @DisplayName("異常系: TEAMスコープで非メンバーが投稿しようとすると403")
        void TEAMスコープで非メンバーが投稿すると403() {
            // given
            Long teamId = 50L;
            CreatePostRequest req = new CreatePostRequest("非メンバー投稿", "TEAM", teamId,
                    "USER", null, null, null, null, null, null);
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, teamId, "TEAM");

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            // postRepository.save は呼ばれないこと（メンバーシップチェックで弾かれる）
            then(postRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("異常系: ORGANIZATIONスコープで非メンバーが投稿しようとすると403")
        void ORGANIZATIONスコープで非メンバーが投稿すると403() {
            // given
            Long orgId = 60L;
            CreatePostRequest req = new CreatePostRequest("非メンバー投稿", "ORGANIZATION", orgId,
                    "USER", null, null, null, null, null, null);
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, orgId, "ORGANIZATION");

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            // postRepository.save は呼ばれないこと（メンバーシップチェックで弾かれる）
            then(postRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("正常系: TEAMスコープでメンバーが投稿すると保存される")
        void TEAMスコープでメンバーが投稿すると保存される() {
            // given: accessControlService.checkMembership は void なのでデフォルト（何もしない）
            Long teamId = 50L;
            CreatePostRequest req = new CreatePostRequest("チームメンバー投稿", "TEAM", teamId,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(accessControlService).checkMembership(USER_ID, teamId, "TEAM");
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでメンバーが投稿すると保存される")
        void ORGANIZATIONスコープでメンバーが投稿すると保存される() {
            // given: accessControlService.checkMembership は void なのでデフォルト（何もしない）
            Long orgId = 70L;
            CreatePostRequest req = new CreatePostRequest("組織メンバー投稿", "ORGANIZATION", orgId,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(accessControlService).checkMembership(USER_ID, orgId, "ORGANIZATION");
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("AC-1: 解決済みscopeId版で投稿すると、slug文字列でなく解決済みLong IDが永続化される")
        void 解決済みscopeId版_解決済みLongIDが永続化される() {
            // given: コントローラーが slug "team-000092" を内部ID 92 に解決して渡すケース
            Long resolvedTeamId = 92L;
            CreatePostRequest req = new CreatePostRequest(
                    "チーム投稿", "TEAM", "team-000092", "USER", null, null, null,
                    null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class)))
                    .willReturn(createPostResponse());

            // when: 3引数版（解決済みID版）を直接呼ぶ
            timelinePostService.createPost(req, resolvedTeamId, USER_ID);

            // then: 永続化された scopeId は slug ではなく解決済み Long、会員チェックも解決済みIDで
            verify(accessControlService).checkMembership(USER_ID, resolvedTeamId, "TEAM");
            ArgumentCaptor<TimelinePostEntity> cap = ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(postRepository).save(cap.capture());
            assertThat(cap.getValue().getScopeType()).isEqualTo(PostScopeType.TEAM);
            assertThat(cap.getValue().getScopeId()).isEqualTo(resolvedTeamId);
        }

        @Test
        @DisplayName("AC-4: 解決済みscopeId版でも非メンバーは解決済みIDで会員チェックされ403・save非実行")
        void 解決済みscopeId版_非メンバーは403() {
            // given: slug は解決済みで内部ID 92 が渡る。その ID で会員チェックが効くこと
            Long resolvedTeamId = 92L;
            CreatePostRequest req = new CreatePostRequest(
                    "非メンバー投稿", "TEAM", "team-000092", "USER", null, null, null,
                    null, null, null, null, null);
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, resolvedTeamId, "TEAM");

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, resolvedTeamId, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            then(postRepository).shouldHaveNoInteractions();
        }
    }

    // ========================================
    // createSystemPost
    // ========================================
    @Nested
    @DisplayName("createSystemPost")
    class CreateSystemPost {

        @Test
        @DisplayName("正常系: TEAMスコープで非メンバーユーザーでもメンバーシップチェックなしで投稿できる")
        void TEAMスコープで非メンバーでもシステム投稿できる() {
            // given: accessControlService は一切呼ばれない想定
            Long teamId = 50L;
            CreatePostRequest req = new CreatePostRequest("【物件履歴】自動投稿テスト", "TEAM", teamId,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createSystemPost(req, USER_ID);

            // then: 正常に投稿が作成される
            assertThat(result).isNotNull();
            verify(postRepository).save(any(TimelinePostEntity.class));
            // メンバーシップチェックは呼ばれないこと
            then(accessControlService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでもメンバーシップチェックなしで投稿できる")
        void ORGANIZATIONスコープでもシステム投稿できる() {
            // given: accessControlService は一切呼ばれない想定
            Long orgId = 70L;
            CreatePostRequest req = new CreatePostRequest("【物件履歴】組織自動投稿", "ORGANIZATION", orgId,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createSystemPost(req, USER_ID);

            // then: 正常に投稿が作成される
            assertThat(result).isNotNull();
            verify(postRepository).save(any(TimelinePostEntity.class));
            // メンバーシップチェックは呼ばれないこと
            then(accessControlService).shouldHaveNoInteractions();
        }
    }

    // ========================================
    // updatePost
    // ========================================
    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("正常系: 投稿を更新し編集履歴が記録される")
        void 投稿を更新し編集履歴が記録される() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("更新内容");
            PostResponse expected = createPostResponse();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(editRepository.save(any(TimelinePostEditEntity.class)))
                    .willReturn(TimelinePostEditEntity.builder().build());
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.updatePost(POST_ID, req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(editRepository).save(any(TimelinePostEditEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しない投稿を更新しようとするとエラー")
        void 存在しない投稿を更新しようとするとエラー() {
            // given
            UpdatePostRequest req = new UpdatePostRequest("更新");
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 他人の投稿を更新しようとするとエラー")
        void 他人の投稿を更新しようとするとエラー() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("更新");
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("異常系: 空コンテンツで更新しようとするとエラー")
        void 空コンテンツで更新しようとするとエラー() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("");
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.EMPTY_POST_CONTENT));
        }
    }

    // ========================================
    // deletePost
    // ========================================
    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("正常系: 投稿を論理削除できる")
        void 投稿を論理削除できる() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            // F13 Phase 4-γ: 添付ファイルなしの場合
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(List.of());

            // when
            timelinePostService.deletePost(POST_ID, USER_ID);

            // then
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("異常系: 他人の投稿は削除できない")
        void 他人の投稿は削除できない() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            // validateOwner が先にスローするため attachmentRepository は呼ばれない

            // when & then
            assertThatThrownBy(() -> timelinePostService.deletePost(POST_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }
    }

    // ========================================
    // getFeed
    // ========================================
    @Nested
    @DisplayName("getFeed")
    class GetFeed {

        @Test
        @DisplayName("正常系: スコープ別フィードを取得できる")
        void スコープ別フィードを取得できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.findFeedByScopeType(eq(PostScopeType.PUBLIC), eq(0L), any(PageRequest.class)))
                    .willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.getFeed("PUBLIC", 0L, null, 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: sizeが0以下の場合はデフォルトサイズ20件で取得する")
        void sizeが0以下の場合はデフォルトサイズで取得する() {
            // given
            given(postRepository.findFeedByScopeType(eq(PostScopeType.PUBLIC), eq(0L), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.getFeed("PUBLIC", 0L, null, 0);

            // then
            verify(postRepository).findFeedByScopeType(eq(PostScopeType.PUBLIC), eq(0L), eq(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("正常系: VILLAGEスコープはscopeVillageIdで絞り込まれる")
        void VILLAGEスコープはscopeVillageIdで絞り込まれる() {
            // given
            UUID villageId = UUID.randomUUID();
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.findFeedByVillageId(eq(villageId), any(PageRequest.class)))
                    .willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.getFeed("VILLAGE", 0L, villageId, 20);

            // then
            assertThat(result).hasSize(1);
            verify(postRepository).findFeedByVillageId(eq(villageId), any(PageRequest.class));
        }

        @Test
        @DisplayName("正常系: VILLAGEスコープでscopeVillageIdがnullの場合は空リストを返す")
        void VILLAGEスコープでscopeVillageIdがnullの場合は空リストを返す() {
            // when
            List<PostResponse> result = timelinePostService.getFeed("VILLAGE", 0L, null, 20);

            // then
            assertThat(result).isEmpty();
            verify(postRepository, org.mockito.Mockito.never())
                    .findFeedByVillageId(any(), any());
        }
    }

    // ========================================
    // getMyFeed（個人集約タイムライン enrich）
    // ========================================
    @Nested
    @DisplayName("getMyFeed enrich（投稿元・著者・代理主体の付与）")
    class GetMyFeedEnrich {

        private static final Long TEAM_ID = 50L;
        private static final Long ORG_ID = 70L;

        /** enrich 前の生 PostResponse（生 ID のみ・name/slug/user/postedAs 未設定）を作る。 */
        private PostResponse rawPost(long id, String scopeType, Long scopeId,
                                     Long userId, String postedAsType, Long postedAsId) {
            return PostResponse.builder()
                    .id(id)
                    .scope(new PostResponse.PostScopeDto(scopeType, scopeId))
                    .author(new PostResponse.PostAuthorDto(userId, null, postedAsType, postedAsId))
                    .content(new PostResponse.PostContentDto("本文", null, null, "PUBLISHED", null, false))
                    .stats(new PostResponse.PostStatsDto(0, 0, 0, (short) 0, (short) 0))
                    .audit(new PostResponse.PostAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                    .build();
        }

        /** membership・repo・mapper の共通スタブ（mapper は与えた raw リストをそのまま返す）。 */
        private void givenFeed(List<PostResponse> rawPosts) {
            given(membershipService.getActiveTeamIdsByUser(USER_ID)).willReturn(List.of(TEAM_ID));
            given(membershipService.getActiveOrgIdsByUser(USER_ID)).willReturn(List.of(ORG_ID));
            given(postRepository.findMyFeed(anyList(), anyList(), any(), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(rawPosts);
        }

        @Test
        @DisplayName("AC-1: TEAM発にteamName+slug、ORG発にorgName+slugが付与される")
        void AC1_投稿元のname_slugが付与される() {
            // given
            PostResponse teamPost = rawPost(1L, "TEAM", TEAM_ID, 100L, "USER", null);
            PostResponse orgPost = rawPost(2L, "ORGANIZATION", ORG_ID, 101L, "USER", null);
            givenFeed(List.of(teamPost, orgPost));

            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of(TEAM_ID, "チームA"));
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of(ORG_ID, "組織B"));
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of(ORG_ID, "org-b"));
            given(nameResolverService.resolveTeamIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserAvatarUrls(anySet())).willReturn(Map.of());

            // when
            List<PostResponse> result = timelinePostService.getMyFeed(USER_ID, null, 20);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getScope().name()).isEqualTo("チームA");
            assertThat(result.get(0).getScope().slug()).isEqualTo("team-a");
            assertThat(result.get(1).getScope().name()).isEqualTo("組織B");
            assertThat(result.get(1).getScope().slug()).isEqualTo("org-b");
        }

        @Test
        @DisplayName("AC-2: 著者 user{displayName, avatarUrl} が付与される")
        void AC2_著者のuserが付与される() {
            // given
            PostResponse teamPost = rawPost(1L, "TEAM", TEAM_ID, 100L, "USER", null);
            givenFeed(List.of(teamPost));

            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of(TEAM_ID, "チームA"));
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of());
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveTeamIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of(100L, "山田太郎"));
            given(nameResolverService.resolveUserAvatarUrls(anySet()))
                    .willReturn(Map.of(100L, "https://cdn/avatar.png"));

            // when
            List<PostResponse> result = timelinePostService.getMyFeed(USER_ID, null, 20);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUser()).isNotNull();
            assertThat(result.get(0).getUser().id()).isEqualTo(100L);
            assertThat(result.get(0).getUser().displayName()).isEqualTo("山田太郎");
            assertThat(result.get(0).getUser().avatarUrl()).isEqualTo("https://cdn/avatar.png");
        }

        @Test
        @DisplayName("AC-3: postedAs=TEAM で代理主体名/ロゴが付与され、USER は null")
        void AC3_代理主体が付与されUSERはnull() {
            // given: 1件は TEAM 代理投稿、1件は USER 投稿
            Long proxyTeamId = 55L;
            PostResponse proxyPost = rawPost(1L, "TEAM", TEAM_ID, 100L, "TEAM", proxyTeamId);
            PostResponse userPost = rawPost(2L, "TEAM", TEAM_ID, 101L, "USER", null);
            givenFeed(List.of(proxyPost, userPost));

            given(nameResolverService.resolveTeamNames(anySet()))
                    .willReturn(Map.of(TEAM_ID, "チームA", proxyTeamId, "代理チーム"));
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of());
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveTeamIconUrls(anySet()))
                    .willReturn(Map.of(proxyTeamId, "https://cdn/logo.png"));
            given(nameResolverService.resolveOrganizationIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserAvatarUrls(anySet())).willReturn(Map.of());

            // when
            List<PostResponse> result = timelinePostService.getMyFeed(USER_ID, null, 20);

            // then
            assertThat(result.get(0).getPostedAs()).isNotNull();
            assertThat(result.get(0).getPostedAs().type()).isEqualTo("TEAM");
            assertThat(result.get(0).getPostedAs().id()).isEqualTo(proxyTeamId);
            assertThat(result.get(0).getPostedAs().name()).isEqualTo("代理チーム");
            assertThat(result.get(0).getPostedAs().displayName()).isEqualTo("代理チーム");
            assertThat(result.get(0).getPostedAs().logoUrl()).isEqualTo("https://cdn/logo.png");
            // USER 投稿は postedAs 無し
            assertThat(result.get(1).getPostedAs()).isNull();
        }

        @Test
        @DisplayName("AC-4: 投稿N件でも各バッチ解決は種別ごとに1回だけ呼ばれる（N+1回避）")
        void AC4_バッチ解決は種別ごとに1回のみ() {
            // given: 3件（別々のチーム・別々の著者）
            List<PostResponse> raws = List.of(
                    rawPost(1L, "TEAM", 51L, 100L, "USER", null),
                    rawPost(2L, "TEAM", 52L, 101L, "USER", null),
                    rawPost(3L, "TEAM", 53L, 102L, "USER", null));
            givenFeed(raws);

            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of());
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveTeamIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserAvatarUrls(anySet())).willReturn(Map.of());

            // when
            timelinePostService.getMyFeed(USER_ID, null, 20);

            // then: 各解決メソッドは 3 投稿分ではなく 1 回のみ呼ばれる
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveTeamNames(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveOrganizationNames(anySet());
            verify(teamService, org.mockito.Mockito.times(1)).getSlugsByIds(anySet());
            verify(organizationService, org.mockito.Mockito.times(1)).getSlugsByIds(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveTeamIconUrls(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveOrganizationIconUrls(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserDisplayNames(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserAvatarUrls(anySet());
            // 著者IDの和集合（100/101/102）が 1 回で渡ること
            ArgumentCaptor<Set<Long>> cap = ArgumentCaptor.forClass(Set.class);
            verify(nameResolverService).resolveUserDisplayNames(cap.capture());
            assertThat(cap.getValue()).containsExactlyInAnyOrder(100L, 101L, 102L);
        }

        @Test
        @DisplayName("AC-5: 退会/削除で名前解決できない場合は既定文言へフォールバック（例外なし）")
        void AC5_フォールバックで例外を投げない() {
            // given: 全解決マップが空（team削除・user退会を模す）
            PostResponse teamPost = rawPost(1L, "TEAM", TEAM_ID, 100L, "USER", null);
            givenFeed(List.of(teamPost));

            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of());
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveTeamIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationIconUrls(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of());
            given(nameResolverService.resolveUserAvatarUrls(anySet())).willReturn(Map.of());

            // when
            List<PostResponse> result = timelinePostService.getMyFeed(USER_ID, null, 20);

            // then: フォールバック文言・slug/avatar は null・例外なし
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScope().name()).isEqualTo("不明なチーム");
            assertThat(result.get(0).getScope().slug()).isNull();
            assertThat(result.get(0).getUser().displayName()).isEqualTo("不明なユーザー");
            assertThat(result.get(0).getUser().avatarUrl()).isNull();
        }

        @Test
        @DisplayName("正常系: 所属が空なら repo を叩かず空リスト（enrich も走らない）")
        void 所属が空なら空リスト() {
            // given
            given(membershipService.getActiveTeamIdsByUser(USER_ID)).willReturn(List.of());
            given(membershipService.getActiveOrgIdsByUser(USER_ID)).willReturn(List.of());

            // when
            List<PostResponse> result = timelinePostService.getMyFeed(USER_ID, null, 20);

            // then
            assertThat(result).isEmpty();
            verify(postRepository, org.mockito.Mockito.never())
                    .findMyFeed(anyList(), anyList(), any(), any(PageRequest.class));
            then(nameResolverService).shouldHaveNoInteractions();
        }
    }

    // ========================================
    // togglePin
    // ========================================
    @Nested
    @DisplayName("togglePin")
    class TogglePin {

        @Test
        @DisplayName("正常系: ピン留め状態を切り替えられる")
        void ピン留め状態を切り替えられる() {
            // given
            TimelinePostEntity post = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.togglePin(POST_ID, true, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }
    }

    // ========================================
    // searchPosts
    // ========================================
    @Nested
    @DisplayName("searchPosts")
    class SearchPosts {

        @Test
        @DisplayName("正常系: キーワードで投稿を検索できる")
        void キーワードで投稿を検索できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.searchByKeyword(eq("テスト"), eq(10))).willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.searchPosts("テスト", 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: limitが0以下の場合はデフォルトサイズ20で検索する")
        void limitが0以下の場合はデフォルトサイズで検索する() {
            // given
            given(postRepository.searchByKeyword(eq("テスト"), eq(20))).willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.searchPosts("テスト", 0);

            // then
            verify(postRepository).searchByKeyword("テスト", 20);
        }
    }

    // ========================================
    // getPostDetail
    // ========================================
    @Nested
    @DisplayName("getPostDetail")
    class GetPostDetail {

        @Test
        @DisplayName("正常系: 投稿詳細を取得できる")
        void 投稿詳細を取得できる() {
            // given
            TimelinePostEntity post = createPost();
            List<TimelinePostAttachmentEntity> attachments = List.of();
            List<AttachmentResponse> attachmentResponses = List.of();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(attachments);
            given(timelineMapper.toAttachmentResponseList(attachments)).willReturn(attachmentResponses);
            given(pollService.getPollByPostId(POST_ID, USER_ID)).willReturn(null);

            // when
            PostDetailResponse result = timelinePostService.getPostDetail(POST_ID, USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getContent().content()).isEqualTo("テスト投稿");
            assertThat(result.getAuthor().userId()).isEqualTo(USER_ID);
        }

        // リアクションサマリーテストは絵文字リアクション機能（countByPostIdGroupByEmoji）実装時に追加予定

        @Test
        @DisplayName("異常系: 投稿が存在しない場合はエラー")
        void 投稿が存在しない場合はエラー() {
            // given
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelinePostService.getPostDetail(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-13: recentReplies は会話の古い順（createdAt昇順）・先頭最大5件に切られ enrich される")
        void AC13_recentRepliesが含まれる() {
            // given: 親投稿。返信は 6 件以上存在するが、詳細は先頭5件のみプレビューする。
            // 単体テストではリポジトリ mock が LIMIT を適用しないため、
            // 「PageRequest.of(0,5) で問い合わせる」ことで DB 側 LIMIT 5 を強制する契約を検証する。
            // mock 返却は LIMIT 5 の DB 結果を模し、ASC 先頭5件（id/createdAt 昇順の 11..15）とする。
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(List.of());
            given(timelineMapper.toAttachmentResponseList(any())).willReturn(List.of());
            given(pollService.getPollByPostId(POST_ID, USER_ID)).willReturn(null);

            // DB は createdAt 昇順・先頭5件（id 11〜15）を返す。id=16 の6件目は LIMIT 5 で含まれない。
            List<TimelinePostEntity> firstFiveEntities = List.of(
                    createPost(), createPost(), createPost(), createPost(), createPost());
            given(postRepository.findRepliesByParentId(eq(POST_ID), eq(PageRequest.of(0, 5))))
                    .willReturn(firstFiveEntities);
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of(
                    rawFeedPost(11L, "TEAM", 50L, 100L, "USER", null),
                    rawFeedPost(12L, "TEAM", 50L, 101L, "USER", null),
                    rawFeedPost(13L, "TEAM", 50L, 102L, "USER", null),
                    rawFeedPost(14L, "TEAM", 50L, 103L, "USER", null),
                    rawFeedPost(15L, "TEAM", 50L, 104L, "USER", null)));
            stubAllResolversEmpty();
            given(nameResolverService.resolveUserDisplayNames(anySet()))
                    .willReturn(Map.of(100L, "返信者A"));

            // when
            PostDetailResponse result = timelinePostService.getPostDetail(POST_ID, USER_ID);

            // then: 先頭5件に切られる（6件目 id=16 は含まれない）
            assertThat(result.getRecentReplies()).isNotNull();
            assertThat(result.getRecentReplies()).hasSize(5);
            assertThat(result.getRecentReplies()).extracting(PostResponse::getId)
                    .doesNotContain(16L);
            // 会話の古い順（createdAt昇順＝id 11→15）で並ぶこと（順序が保たれる）
            assertThat(result.getRecentReplies()).extracting(PostResponse::getId)
                    .containsExactly(11L, 12L, 13L, 14L, 15L);
            // enrich が適用されている（著者名の付与）
            assertThat(result.getRecentReplies().get(0).getUser()).isNotNull();
            assertThat(result.getRecentReplies().get(0).getUser().displayName()).isEqualTo("返信者A");
            // 先頭5件に制限するため PageRequest(0,5) で取得すること（DB 側 LIMIT が上限の実体）
            verify(postRepository).findRepliesByParentId(eq(POST_ID), eq(PageRequest.of(0, 5)));
        }
    }

    // ========================================
    // getUserPosts
    // ========================================
    @Nested
    @DisplayName("getUserPosts")
    class GetUserPosts {

        @Test
        @DisplayName("正常系: ユーザー投稿一覧を取得できる")
        void ユーザー投稿一覧を取得できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.getUserPosts(USER_ID, 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: sizeが0以下の場合はデフォルトサイズで取得する")
        void sizeが0以下の場合はデフォルトサイズで取得する() {
            // given
            given(postRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.getUserPosts(USER_ID, 0);

            // then
            verify(postRepository).findByUserIdOrderByCreatedAtDesc(eq(USER_ID), eq(PageRequest.of(0, 20)));
        }
    }

    // ========================================
    // getReplies
    // ========================================
    @Nested
    @DisplayName("getReplies")
    class GetReplies {

        @Test
        @DisplayName("正常系: リプライ一覧を取得できる（enrich 適用・カーソル対応）")
        void リプライ一覧を取得できる() {
            // given
            Long parentId = 5L;
            List<TimelinePostEntity> replies = List.of(createPost());
            List<PostResponse> mapped = List.of(createPostResponse());

            given(postRepository.findRepliesByParentIdAfterCursor(eq(parentId), any(), any(PageRequest.class)))
                    .willReturn(replies);
            given(timelineMapper.toPostResponseList(replies)).willReturn(mapped);

            // when
            List<PostResponse> result = timelinePostService.getReplies(parentId, null, 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: sizeが0以下の場合はデフォルトサイズで取得する")
        void sizeが0以下_デフォルトサイズ() {
            // given
            Long parentId = 5L;
            given(postRepository.findRepliesByParentIdAfterCursor(eq(parentId), any(), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.getReplies(parentId, null, -1);

            // then
            verify(postRepository).findRepliesByParentIdAfterCursor(eq(parentId), isNull(), eq(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("正常系: cursor 指定時はそのカーソルでリポジトリを呼ぶ")
        void cursor指定時はカーソルでリポジトリを呼ぶ() {
            // given
            Long parentId = 5L;
            Long cursor = 42L;
            given(postRepository.findRepliesByParentIdAfterCursor(eq(parentId), eq(cursor), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.getReplies(parentId, cursor, 20);

            // then
            verify(postRepository).findRepliesByParentIdAfterCursor(eq(parentId), eq(cursor), eq(PageRequest.of(0, 20)));
        }
    }

    // ========================================
    // getFeed enrich（AC-1〜4,6: フィードにも著者・投稿元・代理主体を付与）
    // ========================================
    @Nested
    @DisplayName("getFeed enrich（PR: タイムライン著者名/投稿元/代理主体の付与）")
    class GetFeedEnrich {

        private static final Long TEAM_ID = 50L;
        private static final Long ORG_ID = 70L;

        private void givenFeedEntities() {
            given(postRepository.findFeedByScopeType(any(PostScopeType.class), any(), any(PageRequest.class)))
                    .willReturn(List.of(createPost()));
        }

        @Test
        @DisplayName("AC-1 getFeed: 各投稿に著者 user{displayName, avatarUrl} が付与される（非null）")
        void AC1_getFeed_userが付与される() {
            // given
            givenFeedEntities();
            given(timelineMapper.toPostResponseList(any()))
                    .willReturn(List.of(rawFeedPost(1L, "TEAM", TEAM_ID, 100L, "USER", null)));
            stubAllResolversEmpty();
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of(100L, "山田太郎"));
            given(nameResolverService.resolveUserAvatarUrls(anySet())).willReturn(Map.of(100L, "https://cdn/a.png"));
            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of(TEAM_ID, "チームA"));
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));

            // when
            List<PostResponse> result = timelinePostService.getFeed("TEAM", TEAM_ID, null, 20);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUser()).isNotNull();
            assertThat(result.get(0).getUser().id()).isEqualTo(100L);
            assertThat(result.get(0).getUser().displayName()).isEqualTo("山田太郎");
            assertThat(result.get(0).getUser().avatarUrl()).isEqualTo("https://cdn/a.png");
        }

        @Test
        @DisplayName("AC-2 getFeed: 代理投稿 postedAs(TEAM) の名前/ロゴが付与され、USER は null")
        void AC2_getFeed_代理主体が付与される() {
            // given: 1件は TEAM 代理投稿、1件は USER 投稿
            Long proxyTeamId = 55L;
            givenFeedEntities();
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of(
                    rawFeedPost(1L, "TEAM", TEAM_ID, 100L, "TEAM", proxyTeamId),
                    rawFeedPost(2L, "TEAM", TEAM_ID, 101L, "USER", null)));
            stubAllResolversEmpty();
            given(nameResolverService.resolveTeamNames(anySet()))
                    .willReturn(Map.of(TEAM_ID, "チームA", proxyTeamId, "代理チーム"));
            given(nameResolverService.resolveTeamIconUrls(anySet()))
                    .willReturn(Map.of(proxyTeamId, "https://cdn/logo.png"));

            // when
            List<PostResponse> result = timelinePostService.getFeed("TEAM", TEAM_ID, null, 20);

            // then
            assertThat(result.get(0).getPostedAs()).isNotNull();
            assertThat(result.get(0).getPostedAs().type()).isEqualTo("TEAM");
            assertThat(result.get(0).getPostedAs().id()).isEqualTo(proxyTeamId);
            assertThat(result.get(0).getPostedAs().name()).isEqualTo("代理チーム");
            assertThat(result.get(0).getPostedAs().logoUrl()).isEqualTo("https://cdn/logo.png");
            assertThat(result.get(1).getPostedAs()).isNull();
        }

        @Test
        @DisplayName("AC-3 getFeed: scope.name/slug は TEAM/ORGANIZATION のみ付与、PUBLIC は null")
        void AC3_getFeed_scope名slugはTEAM_ORGのみ() {
            // given: TEAM / ORGANIZATION / PUBLIC の3件
            givenFeedEntities();
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of(
                    rawFeedPost(1L, "TEAM", TEAM_ID, 100L, "USER", null),
                    rawFeedPost(2L, "ORGANIZATION", ORG_ID, 101L, "USER", null),
                    rawFeedPost(3L, "PUBLIC", 0L, 102L, "USER", null)));
            stubAllResolversEmpty();
            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of(TEAM_ID, "チームA"));
            given(nameResolverService.resolveOrganizationNames(anySet())).willReturn(Map.of(ORG_ID, "組織B"));
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));
            given(organizationService.getSlugsByIds(anySet())).willReturn(Map.of(ORG_ID, "org-b"));

            // when
            List<PostResponse> result = timelinePostService.getFeed("PUBLIC", 0L, null, 20);

            // then
            assertThat(result.get(0).getScope().name()).isEqualTo("チームA");
            assertThat(result.get(0).getScope().slug()).isEqualTo("team-a");
            assertThat(result.get(1).getScope().name()).isEqualTo("組織B");
            assertThat(result.get(1).getScope().slug()).isEqualTo("org-b");
            // PUBLIC は enrich 対象外 → name/slug は null のまま
            assertThat(result.get(2).getScope().name()).isNull();
            assertThat(result.get(2).getScope().slug()).isNull();
        }

        @Test
        @DisplayName("AC-4 getFeed: 投稿N件でも各バッチ解決は種別ごとに1回だけ（N+1回避）")
        void AC4_getFeed_バッチ解決は種別ごとに1回のみ() {
            // given: 別々のチーム・別々の著者 3件
            givenFeedEntities();
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of(
                    rawFeedPost(1L, "TEAM", 51L, 100L, "USER", null),
                    rawFeedPost(2L, "TEAM", 52L, 101L, "USER", null),
                    rawFeedPost(3L, "TEAM", 53L, 102L, "USER", null)));
            stubAllResolversEmpty();

            // when
            timelinePostService.getFeed("TEAM", 51L, null, 20);

            // then: 各解決メソッドは 3 投稿分ではなく 1 回のみ
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveTeamNames(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveOrganizationNames(anySet());
            verify(teamService, org.mockito.Mockito.times(1)).getSlugsByIds(anySet());
            verify(organizationService, org.mockito.Mockito.times(1)).getSlugsByIds(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveTeamIconUrls(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveOrganizationIconUrls(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserDisplayNames(anySet());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserAvatarUrls(anySet());
        }

        @Test
        @DisplayName("AC-6 getFeed: 投稿0件（enrich対象IDが空）でも例外なく空リストを返す（解決は呼ばない）")
        void AC6_getFeed_空でも安全() {
            // given
            given(postRepository.findFeedByScopeType(any(PostScopeType.class), any(), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            List<PostResponse> result = timelinePostService.getFeed("PUBLIC", 0L, null, 20);

            // then: 例外なし・空・名前解決は一切呼ばれない
            assertThat(result).isEmpty();
            then(nameResolverService).shouldHaveNoInteractions();
            then(teamService).shouldHaveNoInteractions();
            then(organizationService).shouldHaveNoInteractions();
        }
    }

    // ========================================
    // getPinnedPosts enrich（AC-5）
    // ========================================
    @Nested
    @DisplayName("getPinnedPosts enrich")
    class GetPinnedPostsEnrich {

        private static final Long TEAM_ID = 50L;

        @Test
        @DisplayName("AC-5 getPinnedPosts: ピン留め投稿にも著者/投稿元 enrich が適用される")
        void AC5_getPinnedPosts_enrich適用() {
            // given
            given(postRepository.findPinnedPosts(PostScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(createPost()));
            given(timelineMapper.toPostResponseList(any()))
                    .willReturn(List.of(rawFeedPost(1L, "TEAM", TEAM_ID, 100L, "USER", null)));
            stubAllResolversEmpty();
            given(nameResolverService.resolveTeamNames(anySet())).willReturn(Map.of(TEAM_ID, "チームA"));
            given(teamService.getSlugsByIds(anySet())).willReturn(Map.of(TEAM_ID, "team-a"));
            given(nameResolverService.resolveUserDisplayNames(anySet())).willReturn(Map.of(100L, "山田太郎"));

            // when
            List<PostResponse> result = timelinePostService.getPinnedPosts("TEAM", TEAM_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScope().name()).isEqualTo("チームA");
            assertThat(result.get(0).getScope().slug()).isEqualTo("team-a");
            assertThat(result.get(0).getUser()).isNotNull();
            assertThat(result.get(0).getUser().displayName()).isEqualTo("山田太郎");
        }
    }

    // ========================================
    // getPinnedPosts
    // ========================================
    @Nested
    @DisplayName("getPinnedPosts")
    class GetPinnedPosts {

        @Test
        @DisplayName("正常系: ピン留め投稿一覧を取得できる")
        void ピン留め投稿一覧を取得できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.findPinnedPosts(PostScopeType.PUBLIC, 0L)).willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.getPinnedPosts("PUBLIC", 0L);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createPost 追加パターン
    // ========================================
    @Nested
    @DisplayName("createPost 追加パターン")
    class CreatePostAdditional {

        @Test
        @DisplayName("正常系: 添付ファイル付き投稿を作成できる")
        void 添付ファイル付き投稿を作成できる() {
            // given
            List<CreateAttachmentRequest> attachments = List.of(
                    new CreateAttachmentRequest("IMAGE", "key1", "file1.jpg",
                            1024L, "image/jpeg", (short) 800, (short) 600, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null)
            );
            CreatePostRequest req = new CreatePostRequest("添付付き", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, attachments);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(attachmentRepository.save(any(TimelinePostAttachmentEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(attachmentRepository).save(any(TimelinePostAttachmentEntity.class));
        }

        @Test
        @DisplayName("正常系: contentがnullでもrepostOfIdがあれば作成できる")
        void contentがnullでもrepostOfIdがあれば作成できる() {
            // given
            Long repostOfId = 20L;
            CreatePostRequest req = new CreatePostRequest(null, "PUBLIC", 0L,
                    "USER", null, null, repostOfId, null, null, null);
            TimelinePostEntity savedPost = createPost();
            TimelinePostEntity original = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(postRepository.findById(repostOfId)).willReturn(Optional.of(original));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // togglePin 追加パターン
    // ========================================
    @Nested
    @DisplayName("togglePin 追加パターン")
    class TogglePinAdditional {

        @Test
        @DisplayName("異常系: 他人の投稿のピン留めは変更不可")
        void 他人の投稿のピン留めは変更不可() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.togglePin(POST_ID, true, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }
    }

    // ========================================
    // F13 Phase 4-γ: StorageQuota 統合テスト
    // ========================================
    @Nested
    @DisplayName("F13 Phase 4-γ: StorageQuota 統合")
    class StorageQuotaIntegration {

        private static final Long TEAM_ID = 50L;
        private static final Long ATTACHMENT_ID = 999L;

        @Test
        @DisplayName("正常系: IMAGE 添付付き投稿でcreatePost時にrecordUploadが呼ばれる（TEAM スコープ）")
        void 正常系_IMAGE_createPost_recordUpload_TEAM() {
            // given
            List<CreateAttachmentRequest> attachments = List.of(
                    new CreateAttachmentRequest("IMAGE", "timeline/TEAM/50/tmp/uuid.jpg", "photo.jpg",
                            2048L, "image/jpeg", (short) 800, (short) 600,
                            null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null)
            );
            CreatePostRequest req = new CreatePostRequest("チーム投稿", "TEAM", TEAM_ID,
                    "USER", null, null, null, null, null, attachments);
            TimelinePostEntity savedPost = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .userId(USER_ID)
                    .postedAsType(PostedAsType.USER)
                    .content("チーム投稿")
                    .status(PostStatus.PUBLISHED)
                    .build();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(attachmentRepository.save(any(TimelinePostAttachmentEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostAttachmentEntity entity = inv.getArgument(0);
                        // リフレクションで id を設定
                        try {
                            var f = entity.getClass().getDeclaredField("id");
                            f.setAccessible(true);
                            f.set(entity, ATTACHMENT_ID);
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                        return entity;
                    });
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class)))
                    .willReturn(PostResponse.builder()
                            .id(POST_ID)
                            .scope(new PostResponse.PostScopeDto("TEAM", TEAM_ID))
                            .author(new PostResponse.PostAuthorDto(USER_ID, null, "USER", null))
                            .content(new PostResponse.PostContentDto("チーム投稿", null, null, "PUBLISHED", null, false))
                            .stats(new PostResponse.PostStatsDto(0, 0, 0, (short) 0, (short) 0))
                            .audit(new PostResponse.PostAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                            .build());

            // when
            timelinePostService.createPost(req, USER_ID);

            // then: TEAM スコープで checkQuota → recordUpload が呼ばれる
            then(storageQuotaService).should()
                    .checkQuota(StorageScopeType.TEAM, TEAM_ID, 2048L);
            then(storageQuotaService).should()
                    .recordUpload(eq(StorageScopeType.TEAM), eq(TEAM_ID), eq(2048L),
                            eq(StorageFeatureType.TIMELINE),
                            eq("timeline_post_attachments"), eq(ATTACHMENT_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("正常系: 添付なし投稿でdeletePost時にrecordDeletionは呼ばれない")
        void 正常系_添付なし_deletePost_recordDeletionは呼ばれない() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(List.of());

            // when
            timelinePostService.deletePost(POST_ID, USER_ID);

            // then
            then(storageQuotaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("正常系: deletePost で IMAGE 添付の recordDeletion が呼ばれる")
        void 正常系_deletePost_IMAGE_recordDeletion() {
            // given
            TimelinePostEntity post = createPost(); // PUBLIC スコープ
            TimelinePostAttachmentEntity att = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(POST_ID)
                    .attachmentType(AttachmentType.IMAGE)
                    .fileKey("timeline/PUBLIC/0/tmp/uuid.jpg")
                    .fileSize(4096L)
                    .mimeType("image/jpeg")
                    .sortOrder((short) 0)
                    .build();
            // リフレクションで id 設定
            try {
                var f = att.getClass().getDeclaredField("id");
                f.setAccessible(true);
                f.set(att, ATTACHMENT_ID);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(List.of(att));

            // when
            timelinePostService.deletePost(POST_ID, USER_ID);

            // then: PERSONAL スコープ（PUBLIC はフォールバック）で recordDeletion が呼ばれる
            then(storageQuotaService).should()
                    .recordDeletion(eq(StorageScopeType.PERSONAL), eq(USER_ID), eq(4096L),
                            eq(StorageFeatureType.TIMELINE),
                            eq("timeline_post_attachments"), eq(ATTACHMENT_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("正常系: VIDEO_LINK 型は recordDeletion の対象外")
        void 正常系_deletePost_VIDEO_LINK_recordDeletion対象外() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostAttachmentEntity att = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(POST_ID)
                    .attachmentType(AttachmentType.VIDEO_LINK)
                    .videoUrl("https://youtube.com/watch?v=xxx")
                    .fileSize(null) // URL リンクはファイルサイズなし
                    .sortOrder((short) 0)
                    .build();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(POST_ID))
                    .willReturn(List.of(att));

            // when
            timelinePostService.deletePost(POST_ID, USER_ID);

            // then: VIDEO_LINK は対象外なので recordDeletion は呼ばれない
            then(storageQuotaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("正常系_resolveScope: TEAM スコープ判定")
        void resolveScope_TEAM() {
            TimelinePostService.ScopeResolution scope =
                    timelinePostService.resolveScope("TEAM", TEAM_ID, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("正常系_resolveScope: ORGANIZATION スコープ判定")
        void resolveScope_ORGANIZATION() {
            TimelinePostService.ScopeResolution scope =
                    timelinePostService.resolveScope("ORGANIZATION", 60L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.ORGANIZATION);
            assertThat(scope.scopeId()).isEqualTo(60L);
        }

        @Test
        @DisplayName("正常系_resolveScope: PUBLIC → PERSONAL フォールバック")
        void resolveScope_PUBLIC_PERSONAL() {
            TimelinePostService.ScopeResolution scope =
                    timelinePostService.resolveScope("PUBLIC", 0L, USER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }
    }
}
