package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.timeline.AttachmentType;
import com.mannschaft.app.timeline.event.TimelinePostCreatedEvent;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.AttachmentResponse;
import com.mannschaft.app.timeline.dto.CreateAttachmentRequest;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.ReactionSummaryResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEditEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostAttachmentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostEditRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タイムライン投稿サービス。投稿のCRUD・フィード取得・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostService {

    private static final int MAX_ATTACHMENTS = 10;
    private static final int DEFAULT_FEED_SIZE = 20;

    private final TimelinePostRepository postRepository;
    private final TimelinePostAttachmentRepository attachmentRepository;
    private final TimelinePostEditRepository editRepository;
    private final TimelinePostReactionRepository reactionRepository;
    private final TimelinePollService pollService;
    private final TimelineMapper timelineMapper;
    private final DomainEventPublisher domainEventPublisher;

    /**
     * 投稿を作成する。添付ファイル・投票も同時に作成する。
     *
     * @param req    作成リクエスト
     * @param userId ユーザーID
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req, Long userId) {
        if (req.getContent() == null || req.getContent().isBlank()) {
            if (req.getRepostOfId() == null && req.getPoll() == null) {
                throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
            }
        }

        if (req.getAttachments() != null && req.getAttachments().size() > MAX_ATTACHMENTS) {
            throw new BusinessException(TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED);
        }

        PostStatus status = req.getScheduledAt() != null ? PostStatus.SCHEDULED : PostStatus.PUBLISHED;

        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(PostScopeType.valueOf(req.getScopeTypeOrDefault()))
                .scopeId(req.getScopeIdOrDefault())
                .userId(userId)
                .postedAsType(PostedAsType.valueOf(req.getPostedAsTypeOrDefault()))
                .postedAsId(req.getPostedAsId())
                .parentId(req.getParentId())
                .content(req.getContent())
                .repostOfId(req.getRepostOfId())
                .status(status)
                .scheduledAt(req.getScheduledAt())
                .build();

        post = postRepository.save(post);

        // リプライの場合、親投稿のリプライ数をインクリメント
        if (req.getParentId() != null) {
            postRepository.findById(req.getParentId()).ifPresent(parent -> {
                parent.incrementReplyCount();
                postRepository.save(parent);
            });
        }

        // リポストの場合、元投稿のリポスト数をインクリメント
        if (req.getRepostOfId() != null) {
            postRepository.findById(req.getRepostOfId()).ifPresent(original -> {
                original.incrementRepostCount();
                postRepository.save(original);
            });
        }

        // 添付ファイルの保存
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            saveAttachments(post.getId(), req.getAttachments());
        }

        // 投票の保存
        if (req.getPoll() != null) {
            pollService.createPoll(post.getId(), req.getPoll());
        }

        log.info("タイムライン投稿作成: id={}, userId={}, scopeType={}", post.getId(), userId, req.getScopeTypeOrDefault());

        // 即時公開投稿のみゲーミフィケーションイベントを発行（予約投稿はスキップ）
        if (status == PostStatus.PUBLISHED) {
            domainEventPublisher.publish(new TimelinePostCreatedEvent(
                    post.getId(), userId,
                    req.getScopeTypeOrDefault(),
                    req.getScopeIdOrDefault()
            ));
        }

        return timelineMapper.toPostResponse(post);
    }

    /**
     * 投稿を更新する。編集履歴を記録する。
     *
     * @param postId 投稿ID
     * @param req    更新リクエスト
     * @param userId ユーザーID
     * @return 更新された投稿
     */
    @Transactional
    public PostResponse updatePost(Long postId, UpdatePostRequest req, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        validateOwner(post, userId);

        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
        }

        // 編集履歴の記録
        TimelinePostEditEntity edit = TimelinePostEditEntity.builder()
                .timelinePostId(postId)
                .contentBefore(post.getContent())
                .build();
        editRepository.save(edit);

        post.updateContent(req.getContent());
        post = postRepository.save(post);

        log.info("タイムライン投稿更新: id={}, editCount={}", postId, post.getEditCount());
        return timelineMapper.toPostResponse(post);
    }

    /**
     * 投稿を論理削除する。
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     */
    @Transactional
    public void deletePost(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        validateOwner(post, userId);

        post.softDelete();
        postRepository.save(post);

        log.info("タイムライン投稿削除: id={}, userId={}", postId, userId);
    }

    /**
     * 投稿詳細を取得する。添付ファイル・リアクション集計・投票を含む。
     *
     * @param postId 投稿ID
     * @param userId 閲覧ユーザーID（投票の自分の投票を取得するため）
     * @return 投稿詳細
     */
    public PostDetailResponse getPostDetail(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

        List<AttachmentResponse> attachments = timelineMapper.toAttachmentResponseList(
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId));

        List<ReactionSummaryResponse> reactions = reactionRepository.countByPostIdGroupByEmoji(postId)
                .stream()
                .map(row -> new ReactionSummaryResponse((String) row[0], (Long) row[1]))
                .toList();

        PollResponse pollResponse = pollService.getPollByPostId(postId, userId);

        return new PostDetailResponse(
                post.getId(),
                post.getScopeType().name(),
                post.getScopeId(),
                post.getUserId(),
                post.getSocialProfileId(),
                post.getPostedAsType().name(),
                post.getPostedAsId(),
                post.getParentId(),
                post.getContent(),
                post.getRepostOfId(),
                post.getRepostCount(),
                post.getStatus().name(),
                post.getScheduledAt(),
                post.getIsPinned(),
                post.getReactionCount(),
                post.getReplyCount(),
                post.getAttachmentCount(),
                post.getEditCount(),
                attachments,
                reactions,
                pollResponse,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * スコープ別フィードを取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param size      取得件数
     * @return 投稿一覧
     */
    public List<PostResponse> getFeed(String scopeType, Long scopeId, int size) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> posts = postRepository.findFeedByScopeType(
                scopeType, scopeId, PageRequest.of(0, feedSize));
        return timelineMapper.toPostResponseList(posts);
    }

    /**
     * ユーザーの投稿一覧を取得する。
     *
     * @param userId ユーザーID
     * @param size   取得件数
     * @return 投稿一覧
     */
    public List<PostResponse> getUserPosts(Long userId, int size) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> posts = postRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, feedSize));
        return timelineMapper.toPostResponseList(posts);
    }

    /**
     * 投稿のリプライ一覧を取得する。
     *
     * @param postId 投稿ID
     * @param size   取得件数
     * @return リプライ一覧
     */
    public List<PostResponse> getReplies(Long postId, int size) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> replies = postRepository.findRepliesByParentId(
                postId, PageRequest.of(0, feedSize));
        return timelineMapper.toPostResponseList(replies);
    }

    /**
     * ピン留め投稿一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ピン留め投稿一覧
     */
    public List<PostResponse> getPinnedPosts(String scopeType, Long scopeId) {
        List<TimelinePostEntity> posts = postRepository.findPinnedPosts(scopeType, scopeId);
        return timelineMapper.toPostResponseList(posts);
    }

    /**
     * 全文検索で投稿を取得する。
     *
     * @param keyword 検索キーワード
     * @param limit   取得件数
     * @return 検索結果
     */
    public List<PostResponse> searchPosts(String keyword, int limit) {
        int searchLimit = limit > 0 ? limit : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> posts = postRepository.searchByKeyword(keyword, searchLimit);
        return timelineMapper.toPostResponseList(posts);
    }

    /**
     * 投稿のピン留め状態を切り替える。
     *
     * @param postId 投稿ID
     * @param pinned ピン留めするかどうか
     * @param userId ユーザーID
     * @return 更新された投稿
     */
    @Transactional
    public PostResponse togglePin(Long postId, boolean pinned, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        validateOwner(post, userId);

        post.setPinned(pinned);
        post = postRepository.save(post);

        log.info("タイムライン投稿ピン留め切替: id={}, pinned={}", postId, pinned);
        return timelineMapper.toPostResponse(post);
    }

    // --- プライベートメソッド ---

    /**
     * 投稿を取得する。存在しない場合は例外をスローする。
     */
    private TimelinePostEntity findPostOrThrow(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
    }

    /**
     * 投稿の所有者チェックを行う。
     */
    private void validateOwner(TimelinePostEntity post, Long userId) {
        if (!userId.equals(post.getUserId())) {
            throw new BusinessException(TimelineErrorCode.NOT_POST_OWNER);
        }
    }

    /**
     * 添付ファイルを保存する。
     */
    private void saveAttachments(Long postId, List<CreateAttachmentRequest> attachments) {
        short order = 0;
        for (CreateAttachmentRequest att : attachments) {
            TimelinePostAttachmentEntity entity = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(postId)
                    .attachmentType(AttachmentType.valueOf(att.getAttachmentType()))
                    .fileKey(att.getFileKey())
                    .originalFilename(att.getOriginalFilename())
                    .fileSize(att.getFileSize())
                    .mimeType(att.getMimeType())
                    .imageWidth(att.getImageWidth())
                    .imageHeight(att.getImageHeight())
                    .videoUrl(att.getVideoUrl())
                    .videoThumbnailUrl(att.getVideoThumbnailUrl())
                    .videoTitle(att.getVideoTitle())
                    .linkUrl(att.getLinkUrl())
                    .ogTitle(att.getOgTitle())
                    .ogDescription(att.getOgDescription())
                    .ogImageUrl(att.getOgImageUrl())
                    .ogSiteName(att.getOgSiteName())
                    .sortOrder(att.getSortOrder() != null ? att.getSortOrder() : order)
                    .build();
            attachmentRepository.save(entity);
            order++;
        }
    }
}
