package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.timeline.AttachmentType;
import com.mannschaft.app.timeline.VideoProcessingStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * タイムライン投稿サービス。投稿のCRUD・フィード取得・検索を担当する。
 *
 * <p><b>F13 Phase 4-γ</b>: 投稿作成（添付ファイル含む）時と投稿削除時に
 * {@link StorageQuotaService} を通じてストレージ使用量を計上する。
 * presign 時の checkQuota は {@link TimelineVideoAttachmentService#generateUploadUrl} で実施済み。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostService {

    private static final int MAX_ATTACHMENTS = 10;
    private static final int DEFAULT_FEED_SIZE = 20;

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "timeline_post_attachments";

    private final TimelinePostRepository postRepository;
    private final TimelinePostAttachmentRepository attachmentRepository;
    private final TimelinePostEditRepository editRepository;
    private final TimelinePostReactionRepository reactionRepository;
    private final TimelinePollService pollService;
    private final TimelineMapper timelineMapper;
    private final DomainEventPublisher domainEventPublisher;
    private final R2StorageService r2StorageService;
    /** F13 Phase 4-γ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

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
            ScopeResolution scope = resolveScope(req.getScopeTypeOrDefault(),
                    req.getScopeIdOrDefault(), userId);
            saveAttachments(post.getId(), req.getAttachments(), scope, userId);
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
     * <p><b>F13 Phase 4-γ</b>: 論理削除完了後に添付ファイル（IMAGE / VIDEO_FILE）の
     * 使用量を {@link StorageQuotaService#recordDeletion} で減算する。</p>
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     */
    @Transactional
    public void deletePost(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);
        validateOwner(post, userId);

        // F13 Phase 4-γ: 削除前に添付ファイル情報を取得してクォータ減算に備える
        List<TimelinePostAttachmentEntity> attachments =
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId);

        post.softDelete();
        postRepository.save(post);

        log.info("タイムライン投稿削除: id={}, userId={}", postId, userId);

        // F13 Phase 4-γ: ファイル系添付（IMAGE / VIDEO_FILE）の使用量減算
        ScopeResolution scope = resolveScope(
                post.getScopeType().name(), post.getScopeId(), userId);
        for (TimelinePostAttachmentEntity att : attachments) {
            if (att.getFileSize() == null || att.getFileSize() <= 0) {
                continue;
            }
            AttachmentType type = att.getAttachmentType();
            if (type == AttachmentType.IMAGE || type == AttachmentType.VIDEO_FILE) {
                storageQuotaService.recordDeletion(
                        scope.scopeType(), scope.scopeId(), att.getFileSize(),
                        StorageFeatureType.TIMELINE,
                        REFERENCE_TYPE, att.getId(), userId);
            }
        }
    }

    /**
     * 投稿詳細を取得する。添付ファイル・みたよ！状態・投票を含む。
     *
     * @param postId 投稿ID
     * @param userId 閲覧ユーザーID（みたよ！状態・投票の自分の投票を取得するため）
     * @return 投稿詳細
     */
    public PostDetailResponse getPostDetail(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

        List<AttachmentResponse> attachments = timelineMapper.toAttachmentResponseList(
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId));

        boolean mitayo = reactionRepository.existsByTimelinePostIdAndUserId(postId, userId);
        int mitayoCount = (int) reactionRepository.countByTimelinePostId(postId);

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
                mitayo,
                mitayoCount,
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
     * VIDEO_FILE 型の場合は R2 に対象オブジェクトが存在することを確認する。
     *
     * <p><b>F13 Phase 4-γ</b>: ファイル系添付（IMAGE / VIDEO_FILE）の INSERT 完了後に
     * {@link StorageQuotaService#checkQuota} と {@link StorageQuotaService#recordUpload} を呼ぶ。
     * VIDEO_FILE はファイルキーが設定されている場合のみ計上する（URL 埋め込み動画は対象外）。</p>
     *
     * @param postId      投稿 ID
     * @param attachments 添付ファイルリスト
     * @param scope       解決済みストレージスコープ
     * @param userId      操作者ユーザー ID
     */
    private void saveAttachments(Long postId, List<CreateAttachmentRequest> attachments,
                                  ScopeResolution scope, Long userId) {
        short order = 0;
        for (CreateAttachmentRequest att : attachments) {
            AttachmentType attachmentType = AttachmentType.valueOf(att.getAttachmentType());

            // VIDEO_FILE の場合、R2 にオブジェクトが存在することを確認
            if (attachmentType == AttachmentType.VIDEO_FILE && att.getFileKey() != null) {
                if (!r2StorageService.objectExists(att.getFileKey())) {
                    log.warn("VIDEO_FILE の R2 オブジェクトが見つからない: key={}", att.getFileKey());
                    throw new BusinessException(TimelineErrorCode.ATTACHMENT_NOT_FOUND_IN_STORAGE);
                }
            }

            // F13 Phase 4-γ: ファイル系（IMAGE/VIDEO_FILE）かつ fileSize 有効の場合、クォータ確認
            if ((attachmentType == AttachmentType.IMAGE || attachmentType == AttachmentType.VIDEO_FILE)
                    && att.getFileSize() != null && att.getFileSize() > 0) {
                try {
                    storageQuotaService.checkQuota(scope.scopeType(), scope.scopeId(), att.getFileSize());
                } catch (StorageQuotaExceededException e) {
                    log.info("タイムライン添付クォータ超過: postId={}, userId={}, scope={}/{}, requested={}",
                            postId, userId, scope.scopeType(), scope.scopeId(), e.getRequestedBytes());
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "ストレージ容量が不足しているためアップロードできません");
                }
            }

            // VIDEO_FILE の場合は videoProcessingStatus を PENDING に設定
            VideoProcessingStatus processingStatus = null;
            if (attachmentType == AttachmentType.VIDEO_FILE) {
                String statusStr = att.getVideoProcessingStatus();
                processingStatus = (statusStr != null)
                        ? VideoProcessingStatus.valueOf(statusStr)
                        : VideoProcessingStatus.PENDING;
            }

            TimelinePostAttachmentEntity entity = TimelinePostAttachmentEntity.builder()
                    .timelinePostId(postId)
                    .attachmentType(attachmentType)
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
                    .videoThumbnailKey(att.getVideoThumbnailKey())
                    .videoDurationSeconds(att.getVideoDurationSeconds())
                    .videoCodec(att.getVideoCodec())
                    .videoWidth(att.getVideoWidth())
                    .videoHeight(att.getVideoHeight())
                    .videoProcessingStatus(processingStatus)
                    .build();
            TimelinePostAttachmentEntity saved = attachmentRepository.save(entity);

            // F13 Phase 4-γ: ファイル系添付のクォータ使用量加算
            if ((attachmentType == AttachmentType.IMAGE || attachmentType == AttachmentType.VIDEO_FILE)
                    && att.getFileSize() != null && att.getFileSize() > 0) {
                storageQuotaService.recordUpload(
                        scope.scopeType(), scope.scopeId(), att.getFileSize(),
                        StorageFeatureType.TIMELINE,
                        REFERENCE_TYPE, saved.getId(), userId);
            }

            order++;
        }
    }

    /**
     * タイムライン投稿のスコープ文字列からストレージスコープを解決する。
     *
     * <ul>
     *     <li>TEAM → TEAM スコープ (scopeId = teams.id)</li>
     *     <li>ORGANIZATION → ORGANIZATION スコープ (scopeId = organizations.id)</li>
     *     <li>PUBLIC / PERSONAL / FRIEND_* / その他 → 投稿者の PERSONAL スコープ</li>
     * </ul>
     *
     * @param scopeTypeStr 投稿スコープ文字列（例: "TEAM"）
     * @param scopeId      スコープ ID
     * @param userId       投稿者ユーザー ID（PERSONAL フォールバック用）
     * @return 解決済みスコープ
     */
    ScopeResolution resolveScope(String scopeTypeStr, Long scopeId, Long userId) {
        PostScopeType postScope;
        try {
            postScope = PostScopeType.valueOf(scopeTypeStr);
        } catch (IllegalArgumentException e) {
            return new ScopeResolution(StorageScopeType.PERSONAL, userId);
        }
        return switch (postScope) {
            case TEAM -> new ScopeResolution(StorageScopeType.TEAM, scopeId);
            case ORGANIZATION -> new ScopeResolution(StorageScopeType.ORGANIZATION, scopeId);
            default -> new ScopeResolution(StorageScopeType.PERSONAL, userId);
        };
    }

    /** 解決されたストレージスコープ。 */
    record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}
}
