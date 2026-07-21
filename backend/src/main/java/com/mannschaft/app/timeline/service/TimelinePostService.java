package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.NameResolverService;
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
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    /** 投稿詳細に同梱するリプライプレビュー（会話の古い順・先頭から）の最大件数。 */
    private static final int RECENT_REPLIES_LIMIT = 5;

    /** F13 Phase 4-γ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "timeline_post_attachments";

    /**
     * 認可根治 Wave3-B7-timeline: 村所属が空のユーザーで JPQL の {@code IN ()} 構文エラーを避けるための
     * ダミー UUID（nil UUID）。UUIDv7 は常に非ゼロのタイムスタンプ prefix を持つため実在の村 ID と
     * 衝突しない（{@code findMyFeed} の {@code -1L} ダミーと同じ考え方）。
     */
    private static final UUID NIL_VILLAGE_ID_SENTINEL = new UUID(0L, 0L);

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
    /** F17.1 Phase 3: scope=VILLAGE 投稿の主体検証。 */
    private final PostingIdentityService postingIdentityService;
    /** TEAM/ORGANIZATION スコープへの投稿時のメンバーシップ検証。 */
    private final AccessControlService accessControlService;
    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）の所属スコープ解決用。
     * ドメイン境界原則に従い membership ドメインの Repository を直注入せず Service 経由で利用する
     * （プリミティブ {@code List<Long>} のみを受け取り Entity を漏らさない）。
     */
    private final com.mannschaft.app.membership.service.MembershipService membershipService;
    /**
     * 個人集約タイムラインの投稿元（team/org 名）・著者（表示名/アバター）・代理主体（team/org 名/ロゴ）の
     * バッチ名前解決。N+1 を避けるため種別ごとに 1 回だけ呼ぶ（{@link #enrichPosts}）。
     * ドメイン境界原則に従い team/organization/user の Entity を直参照せず、プリミティブ Map のみを受け取る。
     */
    private final NameResolverService nameResolverService;
    /**
     * 投稿元スコープの slug 一括解決用（TEAM）。ドメイン境界原則に従い Repository を直注入せず
     * Service 経由で slug（プリミティブ）のみを取得する（{@code TimelineScopeIdResolver} と同方針）。
     */
    private final com.mannschaft.app.team.service.TeamService teamService;
    /** 投稿元スコープの slug 一括解決用（ORGANIZATION）。 */
    private final com.mannschaft.app.organization.service.OrganizationService organizationService;

    /** 名前解決フォールバック（退会・削除・匿名化で Map に存在しない場合）。 */
    private static final String UNKNOWN_USER_NAME = "不明なユーザー";
    private static final String UNKNOWN_TEAM_NAME = "不明なチーム";
    private static final String UNKNOWN_ORG_NAME = "不明な組織";

    /**
     * 投稿を作成する（解決済みスコープ ID 版）。添付ファイル・投票も同時に作成する。
     *
     * <p>コントローラーは {@code TimelineScopeIdResolver} で slug/Long 文字列を内部 Long ID に
     * 解決してから本メソッドを呼ぶ。これにより GET feed（解決済み）と対称になり、FE が slug を
     * 送る書き込み経路の 400 を根治する。TEAM/ORGANIZATION スコープへの投稿時は解決済み ID で
     * メンバーシップチェックを行い、非メンバーによる投稿を禁止する。システム内部からの自動投稿には
     * {@link #createSystemPost(CreatePostRequest, Long)} を使うこと。</p>
     *
     * @param req             作成リクエスト
     * @param resolvedScopeId 解決済みの内部スコープ Long ID
     * @param userId          ユーザーID
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req, Long resolvedScopeId, Long userId) {
        checkScopeMembership(req.getScopeTypeOrDefault(), resolvedScopeId, userId);
        return doCreatePost(req, resolvedScopeId, userId, true);
    }

    /**
     * 投稿を作成する（後方互換オーバーロード）。
     *
     * <p>{@code req.getScopeId()} が数値文字列であることを前提に内部 Long ID へ parse して
     * {@link #createPost(CreatePostRequest, Long, Long)} に委譲する。slug 解決を伴わない
     * システム内部・既存呼び出し元（例: {@code TimelinePostAnnouncementAdapter}）・テスト向け。
     * HTTP 経由でユーザーが slug を送るケースはコントローラーで解決済みのため本オーバーロードは通らない。</p>
     *
     * @param req    作成リクエスト（scopeId は数値文字列または null）
     * @param userId ユーザーID
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req, Long userId) {
        return createPost(req, parseInternalScopeId(req), userId);
    }

    /**
     * システム内部からのタイムライン投稿（メンバーシップチェックをスキップ）。
     *
     * <p>ユーザー操作ではなくバッチ/イベント/サービス連携で自動投稿する場合に使う。
     * 例: {@code PropertyWorkPackageService.publishToTimeline()} による物件履歴の自動投稿。</p>
     *
     * <p><strong>注意</strong>: このメソッドは呼び出し元がシステム内部の信頼済みコードであることを
     * 前提とする。ユーザー入力を直接受け付けるコントローラーからは必ず {@link #createPost} を使うこと。</p>
     *
     * @param req    作成リクエスト
     * @param userId 投稿者ユーザーID（システムアクターのID）
     * @return 作成された投稿
     */
    @Transactional
    public PostResponse createSystemPost(CreatePostRequest req, Long userId) {
        return doCreatePost(req, parseInternalScopeId(req), userId, false);
    }

    /**
     * スコープに応じたメンバーシップチェックを行う（ユーザー操作用）。
     *
     * <ul>
     *   <li>TEAM スコープ: {@link AccessControlService#checkMembership} でチームメンバー確認</li>
     *   <li>ORGANIZATION スコープ: 同上で組織メンバー確認</li>
     *   <li>その他スコープ: チェックなし</li>
     * </ul>
     *
     * @param scopeTypeStr    スコープ種別文字列
     * @param resolvedScopeId 解決済みの内部スコープ Long ID
     * @param userId          操作ユーザーID
     */
    private void checkScopeMembership(String scopeTypeStr, Long resolvedScopeId, Long userId) {
        PostScopeType scopeTypeEnum = PostScopeType.valueOf(scopeTypeStr);
        if (scopeTypeEnum == PostScopeType.TEAM && resolvedScopeId != null) {
            accessControlService.checkMembership(userId, resolvedScopeId, "TEAM");
        } else if (scopeTypeEnum == PostScopeType.ORGANIZATION && resolvedScopeId != null) {
            accessControlService.checkMembership(userId, resolvedScopeId, "ORGANIZATION");
        }
    }

    /**
     * システム内部・後方互換経路向けに {@code req.getScopeId()}（数値文字列）を内部 Long ID へ parse する。
     *
     * <p>slug 解決は伴わない（HTTP 経由の slug はコントローラーで解決済み）。null/空文字は {@code 0L}、
     * 数値文字列はそのまま parse する。万一 slug 等の非数値が来た場合は誤った scope への投稿を防ぐため
     * {@link TimelineErrorCode#POST_NOT_FOUND} を投げる（握り潰さず根治）。</p>
     */
    private Long parseInternalScopeId(CreatePostRequest req) {
        String raw = req.getScopeId();
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 投稿作成の共通ロジック（メンバーシップチェックなし）。
     *
     * <p>{@link #createPost} と {@link #createSystemPost} の両方から呼ばれる。
     * バリデーション・ステータス決定・Entity 生成・添付ファイル保存・イベント発行を担う。</p>
     *
     * <p><b>リプライのスコープ継承</b>: {@code parentId} が指定されている場合、リクエストの
     * scopeType/scopeId/scopeVillageId を無視して親投稿のスコープを継承する。
     * これにより「TEAMスコープ投稿へのリプライがPUBLICで作成される」情報漏洩を防ぐ。</p>
     *
     * <p><b>認可根治 Wave6</b>: 継承によってリクエストの申告値と実際に保存されるスコープが
     * 食い違うため、{@code enforceScopeAuthorization} が true の場合は
     * <b>継承後の実効スコープ</b>に対して {@link #requireReplyableParent} で認可を再評価する。
     * 非リプライ時は申告値＝実効値（{@code resolvedScopeId} をそのまま採用する）であり、
     * {@link #createPost} が入口で行う {@link #checkScopeMembership} が実効スコープの検証に一致する。</p>
     *
     * @param req                        作成リクエスト
     * @param resolvedScopeId            解決済みの内部スコープ Long ID（非リプライ時の {@code effectiveScopeId}）
     * @param userId                     投稿者ユーザーID
     * @param enforceScopeAuthorization  実効スコープの認可を評価するか（ユーザー操作は true・
     *                                   {@link #createSystemPost} 経由のシステム自動投稿は false）
     */
    private PostResponse doCreatePost(CreatePostRequest req, Long resolvedScopeId, Long userId,
                                      boolean enforceScopeAuthorization) {
        if (req.getContent() == null || req.getContent().isBlank()) {
            if (req.getRepostOfId() == null && req.getPoll() == null) {
                throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
            }
        }

        if (req.getAttachments() != null && req.getAttachments().size() > MAX_ATTACHMENTS) {
            throw new BusinessException(TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED);
        }

        // F09.13 Phase 2-α-2: 呼び出し元が明示的に DRAFT を指定した場合は尊重する。
        // それ以外は従来通り scheduledAt の有無で SCHEDULED / PUBLISHED を決定する。
        // DRAFT 投稿は TimelinePostRepository の各クエリが status='PUBLISHED' で絞っているため
        // 通常一覧・検索・ピン留め一覧から自動除外される。
        PostStatus status;
        if (req.getStatus() == PostStatus.DRAFT) {
            status = PostStatus.DRAFT;
        } else if (req.getScheduledAt() != null) {
            status = PostStatus.SCHEDULED;
        } else {
            status = PostStatus.PUBLISHED;
        }

        // リプライの場合、親投稿のスコープを継承する（情報漏洩防止）。
        // リクエストで明示されたスコープ値ではなく、必ず親投稿のスコープを正とする。
        // 親が存在しない場合は POST_NOT_FOUND をスローする。
        TimelinePostEntity parentPost = null;
        if (req.getParentId() != null) {
            parentPost = postRepository.findById(req.getParentId())
                    .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
            if (enforceScopeAuthorization) {
                // 認可根治 Wave6: 継承元となる親投稿そのものへの到達可否を先に判定する
                // （読めないスコープの投稿にリプライを積めないようにする）。
                requireReplyableParent(parentPost, userId);
            }
        }

        // 認可根治 Wave6: リポスト元は「呼び出し元から見える投稿」に限る。
        // リプライの継承と同じく、リクエストが渡した投稿 ID をそのまま参照して
        // 書き込み（リポスト数の加算）を行う経路のため、参照先の可視性を先に検証する。
        TimelinePostEntity repostOriginal = null;
        if (req.getRepostOfId() != null) {
            repostOriginal = postRepository.findById(req.getRepostOfId()).orElse(null);
            if (enforceScopeAuthorization && repostOriginal != null
                    && !isPostVisible(repostOriginal, userId)) {
                throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
            }
        }

        // F17.1 Phase 3: scope=VILLAGE 投稿の主体検証
        // リプライ時は親スコープを使用するため req.getScopeTypeOrDefault() は使わない
        PostScopeType scopeTypeEnum;
        Long effectiveScopeId;
        UUID scopeVillageId;
        if (parentPost != null) {
            // リプライ: 親投稿のスコープをそのまま継承する
            scopeTypeEnum = parentPost.getScopeType();
            effectiveScopeId = parentPost.getScopeId();
            scopeVillageId = parentPost.getScopeVillageId();
        } else {
            scopeTypeEnum = PostScopeType.valueOf(req.getScopeTypeOrDefault());
            effectiveScopeId = resolvedScopeId != null ? resolvedScopeId : 0L;
            scopeVillageId = scopeTypeEnum == PostScopeType.VILLAGE ? req.getScopeVillageId() : null;
        }

        PostedAsType postedAsTypeEnum = PostedAsType.valueOf(req.getPostedAsTypeOrDefault());
        Long postedAsId = req.getPostedAsId();

        if (scopeTypeEnum == PostScopeType.VILLAGE) {
            // VILLAGE スコープの検証。リプライで親から村 ID を継承した場合も同じ検証を通す
            // （投稿主体単位の検証はリプライにも等しく必要なため、経路で分岐させない）。
            if (scopeVillageId == null) {
                throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
            }
            // PostedAsType と VillageSubjectType は同名（USER/TEAM/ORGANIZATION）でマッピング可能
            VillageSubjectType subjectType = VillageSubjectType.valueOf(postedAsTypeEnum.name());
            Long subjectId = subjectType == VillageSubjectType.USER
                    ? userId
                    : postedAsId;
            postingIdentityService.validatePostingIdentity(
                    userId, scopeVillageId, subjectType, subjectId);
            // USER の場合は postedAsId に投稿者本人 ID を入れる（既存挙動も同様）
            if (subjectType == VillageSubjectType.USER) {
                postedAsId = userId;
            }
        }

        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(scopeTypeEnum)
                .scopeId(effectiveScopeId)
                .scopeVillageId(scopeVillageId)
                .userId(userId)
                .postedAsType(postedAsTypeEnum)
                .postedAsId(postedAsId)
                .parentId(req.getParentId())
                .content(req.getContent())
                .repostOfId(req.getRepostOfId())
                .status(status)
                .scheduledAt(req.getScheduledAt())
                .build();

        post = postRepository.save(post);

        // リプライの場合、親投稿のリプライ数をインクリメント
        // （親投稿は上記で既に取得済みなのでそれを直接使う）
        if (parentPost != null) {
            parentPost.incrementReplyCount();
            postRepository.save(parentPost);
        }

        // リポストの場合、元投稿のリポスト数をインクリメント
        // （元投稿は上記の可視性検証で既に取得済みなのでそれを直接使う）
        if (repostOriginal != null) {
            repostOriginal.incrementRepostCount();
            postRepository.save(repostOriginal);
        }

        // 添付ファイルの保存
        // リプライ時は継承済みの scopeTypeEnum/effectiveScopeId を使う（req の値は使わない）
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            ScopeResolution scope = resolveScope(scopeTypeEnum.name(), effectiveScopeId, userId);
            saveAttachments(post.getId(), req.getAttachments(), scope, userId);
        }

        // 投票の保存
        if (req.getPoll() != null) {
            pollService.createPoll(post.getId(), req.getPoll());
        }

        log.info("タイムライン投稿作成: id={}, userId={}, scopeType={}", post.getId(), userId, scopeTypeEnum);

        // 即時公開投稿のみゲーミフィケーションイベントを発行（予約投稿はスキップ）
        if (status == PostStatus.PUBLISHED) {
            domainEventPublisher.publish(new TimelinePostCreatedEvent(
                    post.getId(), userId,
                    scopeTypeEnum.name(),
                    effectiveScopeId
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

        // リプライ削除時は親投稿のリプライ数をデクリメントする（作成時 doCreatePost の +1 と対称）。
        // findPostOrThrow は @SQLRestriction("deleted_at IS NULL") により削除済み投稿を取得しないため、
        // 本メソッドに到達した時点で「今回初めて削除される」ことが保証される（二重デクリメント防止＝冪等）。
        // 親が既に削除済み（findById が空）の場合は減算をスキップする（安全）。負値ガードは
        // TimelinePostEntity#decrementReplyCount 内で担保する（0 でクランプ）。
        if (post.getParentId() != null) {
            postRepository.findById(post.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                postRepository.save(parent);
            });
        }

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
        // 認可根治 Wave3-B7-timeline（BOLA 根治）: post を先に取得し、post 自身が持つ scope に対して
        // membership を検証する。不可視なら「存在しない」と同じ POST_NOT_FOUND を返し、
        // 越境アクセスの成否（対象 ID が実在するか）を漏らさない。
        if (!isPostVisible(post, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }

        List<AttachmentResponse> attachments = timelineMapper.toAttachmentResponseList(
                attachmentRepository.findByTimelinePostIdOrderBySortOrderAsc(postId));

        boolean mitayo = reactionRepository.existsByTimelinePostIdAndUserId(postId, userId);
        int mitayoCount = (int) reactionRepository.countByTimelinePostId(postId);

        PollResponse pollResponse = pollService.getPollByPostId(postId, userId);

        // リプライのプレビュー（会話の古い順＝createdAt 昇順・先頭最大 RECENT_REPLIES_LIMIT 件）を enrich して同梱する。
        // リプライ一覧 API の ID 昇順キーセットページングと一貫させ「古い順・先頭N件」で統一する（最新N件ではない）。
        // enrichPosts を通すことで一覧と同じく著者名/アバター・投稿元名/slug・代理主体が付与される。
        List<PostResponse> recentReplies = enrichPosts(timelineMapper.toPostResponseList(
                postRepository.findRepliesByParentId(postId, PageRequest.of(0, RECENT_REPLIES_LIMIT))));

        return PostDetailResponse.builder()
                .id(post.getId())
                .scope(new PostDetailResponse.PostScopeDto(
                        post.getScopeType().name(),
                        post.getScopeId()))
                .author(new PostDetailResponse.PostAuthorDto(
                        post.getUserId(),
                        post.getSocialProfileId(),
                        post.getPostedAsType().name(),
                        post.getPostedAsId()))
                .content(new PostDetailResponse.PostContentDto(
                        post.getContent(),
                        post.getParentId(),
                        post.getRepostOfId(),
                        post.getStatus().name(),
                        post.getScheduledAt(),
                        post.getIsPinned()))
                .stats(new PostDetailResponse.PostStatsDto(
                        post.getRepostCount(),
                        post.getReactionCount(),
                        post.getReplyCount(),
                        post.getAttachmentCount(),
                        post.getEditCount(),
                        mitayoCount,
                        mitayo))
                .attachments(attachments)
                .poll(pollResponse)
                .audit(new PostDetailResponse.PostAuditDto(
                        post.getCreatedAt(),
                        post.getUpdatedAt()))
                .recentReplies(recentReplies)
                .build();
    }

    /**
     * スコープ別フィードを取得する。
     *
     * <p>scopeType=VILLAGE の場合は scopeVillageId（UUID）で絞り込む。
     * scopeVillageId が null の場合は空リストを返す。</p>
     *
     * <p><b>認可根治 Wave3-B7-timeline</b>: TEAM/ORGANIZATION は {@link #checkScopeMembership}
     * （非メンバーは COMMON_002・403）、VILLAGE は {@link #requireVillageMember}
     * （非メンバーは {@link VillageErrorCode#NOT_MEMBER}・IDOR 対策で 404 相当）で
     * 呼び出し元のメンバーシップを検証する。PUBLIC 等その他スコープは従来通り無検証
     * （本来公開のため）。TIMELINE_POST は {@code VisibilityResolver} 未実装のため、
     * {@code contentVisibilityChecker} ではなく明示 membership チェックで是正する。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープID（VILLAGE スコープでは未使用）
     * @param scopeVillageId 村 ID（scopeType=VILLAGE 時に使用）
     * @param size           取得件数
     * @param userId         呼び出し元ユーザー ID（メンバーシップ検証用）
     * @return 投稿一覧
     */
    public List<PostResponse> getFeed(String scopeType, Long scopeId, UUID scopeVillageId, int size, Long userId) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        PostScopeType scopeTypeEnum = PostScopeType.valueOf(scopeType);
        List<TimelinePostEntity> posts;
        if (scopeTypeEnum == PostScopeType.VILLAGE) {
            // 村スコープは scope_village_id（UUID）で絞り込む
            if (scopeVillageId == null) {
                return List.of();
            }
            requireVillageMember(scopeVillageId, userId);
            posts = postRepository.findFeedByVillageId(scopeVillageId, PageRequest.of(0, feedSize));
        } else {
            checkScopeMembership(scopeType, scopeId, userId);
            posts = postRepository.findFeedByScopeType(scopeTypeEnum, scopeId, PageRequest.of(0, feedSize));
        }
        // スコープ別フィードにも著者名/アバター・投稿元名/slug・代理主体を enrich する
        // （マイフィードと同じ enrichPosts を通す）。
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 呼び出し元ユーザーが対象村の現役 USER メンバーであることを検証する（認可根治 Wave3-B7-timeline）。
     * 非メンバーは {@link VillageErrorCode#NOT_MEMBER}（village ドメインの既存 IDOR 対策と同一方針・
     * {@code VillageSearchService#requireVillageMember} を踏襲）。
     */
    private void requireVillageMember(UUID villageId, Long userId) {
        if (!postingIdentityService.isUserVillageMember(villageId, userId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
    }

    /**
     * リプライ先の親投稿に到達できることを検証する（認可根治 Wave6・書き込み経路）。
     *
     * <p>リプライは親投稿のスコープをそのまま継承して保存されるため、リクエストが申告した
     * スコープではなく <b>継承元の親投稿が属する実効スコープ</b> に対して認可を評価する。
     * 判定は読取経路（{@link #getPostDetail} / {@link #getReplies}）と同じ
     * {@link #isPostVisible} を用い、到達できない場合は読取経路と同一の
     * {@link TimelineErrorCode#POST_NOT_FOUND} に倒して対象 ID の実在を秘匿する。</p>
     *
     * <p>VILLAGE スコープだけは本メソッドで判定しない。村への投稿権限は下流の
     * {@link PostingIdentityService#validatePostingIdentity} が
     * <b>投稿主体（USER / TEAM / ORGANIZATION）単位</b>で検証しており、ここで呼び出し元
     * {@code userId} 単位の村メンバー判定を重ねると、チーム／組織としての正当な代理投稿の
     * 判定粒度を落とすことになる。素通しではなく、より粒度の細かい主体検証へ委譲する
     * （{@link #doCreatePost} の VILLAGE ブロックがリプライ経路でも必ず走る）。</p>
     *
     * @param parentPost リプライ先の親投稿
     * @param userId     呼び出し元ユーザー ID
     */
    private void requireReplyableParent(TimelinePostEntity parentPost, Long userId) {
        if (parentPost.getScopeType() == PostScopeType.VILLAGE) {
            return;
        }
        if (!isPostVisible(parentPost, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
    }

    /**
     * 投稿 1 件の可視性を判定する（認可根治 Wave3-B7-timeline・BOLA 対策）。
     * {@link #getPostDetail} / {@link #getReplies} が「post を先に取得し post 自身の scope で判定する」
     * ために使う（クエリパラメータ由来の scope ではなく、DB に永続化された実 scope を正とする）。
     *
     * <ul>
     *   <li>PUBLIC: 常に可視</li>
     *   <li>TEAM/ORGANIZATION: 呼び出し元がそのスコープのメンバーであること</li>
     *   <li>VILLAGE: 呼び出し元がその村の現役 USER メンバーであること</li>
     *   <li>PERSONAL: 呼び出し元が投稿者本人であること</li>
     *   <li>FRIEND_TEAM/FRIEND_FORWARD/FRIEND_ARCHIVE: 本 Wave の対象外（social ドメインの
     *       {@code FriendFeedController} 経由が正規導線であり、本メソッド経由の閲覧は想定外だが
     *       誤検知で正規導線を壊さないよう pass-through。B7 以降の follow-up 候補として残す）</li>
     * </ul>
     */
    private boolean isPostVisible(TimelinePostEntity post, Long userId) {
        return switch (post.getScopeType()) {
            case PUBLIC -> true;
            case TEAM -> accessControlService.isMember(userId, post.getScopeId(), "TEAM");
            case ORGANIZATION -> accessControlService.isMember(userId, post.getScopeId(), "ORGANIZATION");
            case VILLAGE -> post.getScopeVillageId() != null
                    && postingIdentityService.isUserVillageMember(post.getScopeVillageId(), userId);
            case PERSONAL -> userId != null && userId.equals(post.getUserId());
            case FRIEND_TEAM, FRIEND_FORWARD, FRIEND_ARCHIVE -> true;
        };
    }

    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）を取得する。
     *
     * <p>ログインユーザーが所属する全チーム/組織（MEMBER / SUPPORTER 両方）の
     * タイムライン投稿を横断集約し、新しい順（id 降順）で返す。timeline 投稿に
     * 可視性列は無く所属スコープ一致＝可視のため、サポーターもメンバーと完全同一の
     * 投稿が見える。VILLAGE は集約対象外（殿の確定仕様 b）。自分の投稿も含む（仕様 a）。</p>
     *
     * <p>所属スコープ ID は {@link com.mannschaft.app.membership.service.MembershipService}
     * 経由で解決する（ドメイン境界原則）。両メソッドは MEMBER / SUPPORTER 両方を含む。</p>
     *
     * <p>空ガード: 所属チーム・組織が両方空なら repo を呼ばず空リストを返す
     * （JPQL の {@code IN ()} エラー回避）。片方だけ空の場合は実 scopeId と衝突しない
     * ダミー値（{@code -1L}）で埋め、もう一方の OR 条件だけを実効化する。</p>
     *
     * @param userId 認証ユーザー ID
     * @param cursor カーソル（この投稿 id 未満を取得）。null なら最新から
     * @param limit  取得件数（1 件以上）
     * @return マイフィード投稿一覧（id 降順・最大 limit 件）
     */
    public List<PostResponse> getMyFeed(Long userId, Long cursor, int limit) {
        int feedSize = limit > 0 ? limit : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(userId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(userId);

        // 空ガード: 所属がゼロなら DB を叩かず空（JPQL IN () エラー回避）。
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return List.of();
        }

        // 片方だけ空でも JPQL IN :emptyList が DB で問題になりうるため、
        // 実 scopeId（常に正の値）と衝突しないダミー -1L を入れて当該 OR 条件を無効化する。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;

        List<TimelinePostEntity> posts = postRepository.findMyFeed(
                safeTeamIds, safeOrgIds, cursor, PageRequest.of(0, feedSize));
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 投稿群に「投稿元（team/org 名・slug）」「著者（表示名・アバター）」
     * 「代理投稿主体（team/org 名・ロゴ）」をバッチ enrich して付与する。
     *
     * <p>マイフィード・スコープ別フィード・ピン留め一覧・リプライ一覧・投稿詳細の直近リプライの
     * すべてから共通で呼ばれる（myFeed 固有依存は無い）。</p>
     *
     * <p><b>N+1 回避</b>: 全投稿から ID 集合を収集し、名前解決/slug 解決は種別ごとに 1 回だけ呼ぶ
     * （{@link FriendFeedService} の enrich パターンを踏襲）。team/org 名・アイコンは「投稿元スコープ」と
     * 「代理主体」の ID を和集合にして 1 回ずつ解決する。</p>
     *
     * <p><b>null 安全</b>: 退会・匿名化ユーザー／論理削除された team/org は各 Map に含まれないため、
     * 表示名は既定文言（{@value #UNKNOWN_USER_NAME} 等）へフォールバックする（例外を投げない）。
     * postedAsType=USER/SOCIAL_PROFILE の場合は {@code postedAs} を付与せず {@code null} のままとする。
     * 投稿が空（enrich 対象 ID が無い）の場合は解決を一切呼ばずそのまま返す。</p>
     *
     * <p>ドメイン境界: timeline → team/organization/user の参照は Service（NameResolver 等）経由で
     * プリミティブのみを受け取り、Entity を跨いで持ち込まない（新規クロスドメイン FK は作らない）。</p>
     *
     * @param posts マッパー変換済みの投稿レスポンス（生 ID のみ）
     * @return enrich 済みの投稿レスポンス（順序保持）
     */
    private List<PostResponse> enrichPosts(List<PostResponse> posts) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }

        // 1. ID 集合の収集（投稿元スコープ / 著者 / 代理主体を種別ごとに分ける）
        Set<Long> scopeTeamIds = new HashSet<>();
        Set<Long> scopeOrgIds = new HashSet<>();
        Set<Long> authorUserIds = new HashSet<>();
        Set<Long> postedAsTeamIds = new HashSet<>();
        Set<Long> postedAsOrgIds = new HashSet<>();
        for (PostResponse p : posts) {
            if (p.getScope() != null && p.getScope().scopeId() != null) {
                if (PostScopeType.TEAM.name().equals(p.getScope().scopeType())) {
                    scopeTeamIds.add(p.getScope().scopeId());
                } else if (PostScopeType.ORGANIZATION.name().equals(p.getScope().scopeType())) {
                    scopeOrgIds.add(p.getScope().scopeId());
                }
            }
            if (p.getAuthor() != null) {
                if (p.getAuthor().userId() != null) {
                    authorUserIds.add(p.getAuthor().userId());
                }
                Long paId = p.getAuthor().postedAsId();
                if (paId != null) {
                    if (PostedAsType.TEAM.name().equals(p.getAuthor().postedAsType())) {
                        postedAsTeamIds.add(paId);
                    } else if (PostedAsType.ORGANIZATION.name().equals(p.getAuthor().postedAsType())) {
                        postedAsOrgIds.add(paId);
                    }
                }
            }
        }

        // 2. バッチ解決（種別ごとに 1 回のみ = N+1 回避）
        // 名前・アイコンは投稿元スコープと代理主体の ID を和集合にして重複解決を避ける。
        Set<Long> allTeamIds = new HashSet<>(scopeTeamIds);
        allTeamIds.addAll(postedAsTeamIds);
        Set<Long> allOrgIds = new HashSet<>(scopeOrgIds);
        allOrgIds.addAll(postedAsOrgIds);

        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(allTeamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(allOrgIds);
        Map<Long, String> teamSlugs = teamService.getSlugsByIds(scopeTeamIds);
        Map<Long, String> orgSlugs = organizationService.getSlugsByIds(scopeOrgIds);
        Map<Long, String> teamIcons = nameResolverService.resolveTeamIconUrls(postedAsTeamIds);
        Map<Long, String> orgIcons = nameResolverService.resolveOrganizationIconUrls(postedAsOrgIds);
        Map<Long, String> authorNames = nameResolverService.resolveUserDisplayNames(authorUserIds);
        Map<Long, String> authorAvatars = nameResolverService.resolveUserAvatarUrls(authorUserIds);

        // 3. 各投稿へ enrich（toBuilder で不変 DTO を再構築）
        return posts.stream()
                .map(p -> p.toBuilder()
                        .scope(enrichScope(p.getScope(), teamNames, orgNames, teamSlugs, orgSlugs))
                        .user(enrichUser(p.getAuthor(), authorNames, authorAvatars))
                        .postedAs(enrichPostedAs(p.getAuthor(), teamNames, orgNames, teamIcons, orgIcons))
                        .build())
                .toList();
    }

    /** 投稿元スコープに team/org 名・slug を付与する（TEAM/ORGANIZATION のみ。それ以外は素通し）。 */
    private PostResponse.PostScopeDto enrichScope(PostResponse.PostScopeDto scope,
                                                  Map<Long, String> teamNames, Map<Long, String> orgNames,
                                                  Map<Long, String> teamSlugs, Map<Long, String> orgSlugs) {
        if (scope == null || scope.scopeId() == null) {
            return scope;
        }
        if (PostScopeType.TEAM.name().equals(scope.scopeType())) {
            return new PostResponse.PostScopeDto(scope.scopeType(), scope.scopeId(),
                    teamNames.getOrDefault(scope.scopeId(), UNKNOWN_TEAM_NAME),
                    teamSlugs.get(scope.scopeId()));
        }
        if (PostScopeType.ORGANIZATION.name().equals(scope.scopeType())) {
            return new PostResponse.PostScopeDto(scope.scopeType(), scope.scopeId(),
                    orgNames.getOrDefault(scope.scopeId(), UNKNOWN_ORG_NAME),
                    orgSlugs.get(scope.scopeId()));
        }
        return scope;
    }

    /** 著者ユーザー（表示名・アバター）を付与する。userId が無ければ null。 */
    private PostResponse.PostUserDto enrichUser(PostResponse.PostAuthorDto author,
                                                Map<Long, String> authorNames, Map<Long, String> authorAvatars) {
        if (author == null || author.userId() == null) {
            return null;
        }
        Long uid = author.userId();
        return new PostResponse.PostUserDto(
                uid,
                authorNames.getOrDefault(uid, UNKNOWN_USER_NAME),
                authorAvatars.get(uid));
    }

    /** 代理投稿主体（team/org 名・ロゴ）を付与する。postedAsType=USER/SOCIAL_PROFILE は null。 */
    private PostResponse.PostPostedAsDto enrichPostedAs(PostResponse.PostAuthorDto author,
                                                        Map<Long, String> teamNames, Map<Long, String> orgNames,
                                                        Map<Long, String> teamIcons, Map<Long, String> orgIcons) {
        if (author == null || author.postedAsId() == null) {
            return null;
        }
        Long paId = author.postedAsId();
        if (PostedAsType.TEAM.name().equals(author.postedAsType())) {
            String name = teamNames.getOrDefault(paId, UNKNOWN_TEAM_NAME);
            return new PostResponse.PostPostedAsDto("TEAM", paId, name, name, teamIcons.get(paId), null, null);
        }
        if (PostedAsType.ORGANIZATION.name().equals(author.postedAsType())) {
            String name = orgNames.getOrDefault(paId, UNKNOWN_ORG_NAME);
            return new PostResponse.PostPostedAsDto("ORGANIZATION", paId, name, name, orgIcons.get(paId), null, null);
        }
        return null;
    }

    /**
     * ユーザーの投稿一覧を取得する（呼び出し元から可視な scope のみ。認可根治 Wave3-B7-timeline）。
     *
     * <p>旧実装は対象ユーザーの全 PUBLISHED 投稿を scope 無視で返しており、TEAM/PERSONAL 等
     * 呼び出し元が非メンバーの投稿まで漏洩していた（BOLA）。本人が閲覧する場合は scope 不問で
     * 全件、他人が閲覧する場合は PUBLIC + 呼び出し元が所属する TEAM/ORGANIZATION/VILLAGE scope の
     * 投稿のみに限定する（{@link TimelinePostRepository#findByUserIdVisibleToCaller} 参照）。</p>
     *
     * @param targetUserId 投稿一覧の対象ユーザーID
     * @param size         取得件数
     * @param callerUserId 呼び出し元ユーザー ID（可視 scope 解決用）
     * @return 投稿一覧
     */
    public List<PostResponse> getUserPosts(Long targetUserId, int size, Long callerUserId) {
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(callerUserId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(callerUserId);
        List<UUID> villageIds = postingIdentityService.getActiveVillageIdsByUser(callerUserId);
        // 空リストは JPQL の IN () で構文エラーになるためダミー値で埋める（findMyFeed と同一規約）。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;
        List<UUID> safeVillageIds = villageIds.isEmpty() ? List.of(NIL_VILLAGE_ID_SENTINEL) : villageIds;
        List<TimelinePostEntity> posts = postRepository.findByUserIdVisibleToCaller(
                targetUserId, callerUserId, safeTeamIds, safeOrgIds, safeVillageIds,
                PageRequest.of(0, feedSize));
        return timelineMapper.toPostResponseList(posts);
    }

    /**
     * 投稿のリプライ一覧をカーソルページネーションで取得する。
     *
     * <p>著者名/アバター・投稿元名/slug・代理主体を {@link #enrichPosts} で付与する
     * （一覧フィードと同一の表示情報）。ID 昇順で並べ、{@code cursor} 指定時はその ID より後を返す。</p>
     *
     * <p><b>認可根治 Wave3-B7-timeline（BOLA 根治）</b>: 親投稿を取得し、その scope に対して
     * {@link #isPostVisible} で可視性を検証する。不可視なら {@link #getPostDetail} と同様に
     * POST_NOT_FOUND を返す（越境アクセスの成否を漏らさない）。</p>
     *
     * @param postId 親投稿ID
     * @param cursor 起点カーソル（この投稿 ID より後を取得）。null なら先頭から
     * @param size   取得件数（1 件以上・0 以下は既定 20）
     * @param userId 呼び出し元ユーザー ID（親投稿の可視性検証用）
     * @return enrich 済みリプライ一覧（ID 昇順）
     */
    public List<PostResponse> getReplies(Long postId, Long cursor, int size, Long userId) {
        TimelinePostEntity parent = findPostOrThrow(postId);
        if (!isPostVisible(parent, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
        int feedSize = size > 0 ? size : DEFAULT_FEED_SIZE;
        List<TimelinePostEntity> replies = postRepository.findRepliesByParentIdAfterCursor(
                postId, cursor, PageRequest.of(0, feedSize));
        return enrichPosts(timelineMapper.toPostResponseList(replies));
    }

    /**
     * ピン留め投稿一覧を取得する。
     *
     * <p><b>認可根治 Wave3-B7-timeline</b>: TEAM/ORGANIZATION は {@link #checkScopeMembership}
     * で呼び出し元のメンバーシップを検証する（非メンバーは COMMON_002・403）。</p>
     *
     * <p><b>既知の残課題（本 Wave 対象外）</b>: VILLAGE スコープは呼び出し元が
     * {@code scopeVillageId} を渡す経路が無く（本 EP のシグネチャに UUID パラメータが存在しない）、
     * {@code scope_id} は常に 0 のため全村で衝突する。本メソッドの認可検証だけでは
     * VILLAGE のピン留め投稿混在は是正できない（別途 API 契約変更が必要・follow-up 起票推奨）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    呼び出し元ユーザー ID（メンバーシップ検証用）
     * @return ピン留め投稿一覧
     */
    public List<PostResponse> getPinnedPosts(String scopeType, Long scopeId, Long userId) {
        checkScopeMembership(scopeType, scopeId, userId);
        PostScopeType scopeTypeEnum = PostScopeType.valueOf(scopeType);
        List<TimelinePostEntity> posts = postRepository.findPinnedPosts(scopeTypeEnum, scopeId);
        // ピン留め一覧にも著者名/アバター・投稿元名/slug・代理主体を enrich する。
        return enrichPosts(timelineMapper.toPostResponseList(posts));
    }

    /**
     * 全文検索で投稿を取得する（可視 scope 絞り込み込み。認可根治 Wave3-B7-timeline・本丸）。
     *
     * <p>旧実装は {@code MATCH...AGAINST} のみで scope を一切見ておらず、TEAM/ORGANIZATION/
     * PERSONAL の全投稿がキーワード一致で横断ヒットしていた（本文漏洩）。呼び出し元が可視な
     * scope（PUBLIC 常時 + 所属 TEAM/ORGANIZATION + 自分の PERSONAL）に限定する。
     * VILLAGE は本 Wave では対象外（{@link TimelinePostRepository#SEARCH_QUERY} の Javadoc 参照）。</p>
     *
     * @param keyword 検索キーワード
     * @param limit   取得件数
     * @param userId  呼び出し元ユーザー ID（可視 scope 解決・PERSONAL 一致判定用）
     * @return 検索結果
     */
    public List<PostResponse> searchPosts(String keyword, int limit, Long userId) {
        int searchLimit = limit > 0 ? limit : DEFAULT_FEED_SIZE;
        List<Long> teamIds = membershipService.getActiveTeamIdsByUser(userId);
        List<Long> orgIds = membershipService.getActiveOrgIdsByUser(userId);
        // 空リストは native SQL の IN () で構文エラーになるためダミー値で埋める（findMyFeed と同一規約）。
        List<Long> safeTeamIds = teamIds.isEmpty() ? List.of(-1L) : teamIds;
        List<Long> safeOrgIds = orgIds.isEmpty() ? List.of(-1L) : orgIds;
        List<TimelinePostEntity> posts = postRepository.searchByKeyword(
                keyword, safeTeamIds, safeOrgIds, userId, searchLimit);
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
