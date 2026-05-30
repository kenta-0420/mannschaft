package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 掲示板返信サービス。返信のCRUD・ツリー構造取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinReplyService {

    /**
     * 返信ネストの最大 depth（設計書 F05.1 §5: 最大5階層 = depth 0〜4）。
     * 新規返信の depth がこれを超える（= 6階層目）場合は 400 で弾く。
     */
    private static final int MAX_REPLY_DEPTH = 4;

    private final BulletinReplyRepository replyRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinThreadService threadService;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    private final AuditLogService auditLogService;
    /** F17.1 村掲示板グローバル方式: 村スコープの閲覧/モデレーション認可を委譲する。 */
    private final VillageBulletinAccessService villageBulletinAccessService;
    /** F17.1 村掲示板グローバル方式: scope=VILLAGE 返信投稿の主体検証（村メンバー判定を内包）。 */
    private final PostingIdentityService postingIdentityService;

    /**
     * スレッドの返信一覧をページング取得する（トップレベルのみ）。所属メンバーのみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @param pageable  ページング情報
     * @return 返信レスポンスのページ（子返信付き）
     */
    public Page<ReplyResponse> listReplies(ScopeType scopeType, Long scopeId, Long threadId, Long userId, Pageable pageable) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        Page<BulletinReplyEntity> page =
                replyRepository.findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(threadId, pageable);
        return page.map(this::toReplyWithChildren);
    }

    /**
     * 返信を作成する。所属メンバー（SUPPORTER も可）。ロック中は不可。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    投稿者ID
     * @param request   作成リクエスト
     * @return 作成された返信レスポンス
     */
    @Transactional
    public ReplyResponse createReply(ScopeType scopeType, Long scopeId, Long threadId, Long userId, CreateReplyRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        if (!thread.isWritable()) {
            throw new BusinessException(
                    thread.getIsLocked() ? BulletinErrorCode.THREAD_LOCKED : BulletinErrorCode.THREAD_ARCHIVED);
        }

        // 親返信の存在確認 + ネスト深さ算出（設計書 §5: 親 depth + 1。スレッド直下は 0）
        int depth = 0;
        if (request.getParentId() != null) {
            BulletinReplyEntity parent = replyRepository.findByIdAndThreadId(request.getParentId(), threadId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.PARENT_REPLY_MISMATCH));
            int parentDepth = parent.getDepth() != null ? parent.getDepth() : 0;
            depth = parentDepth + 1;
            // 最大5階層（depth 0〜4）。6階層目（depth 5）は 400 で弾く
            if (depth > MAX_REPLY_DEPTH) {
                throw new BusinessException(BulletinErrorCode.REPLY_DEPTH_EXCEEDED);
            }
            parent.incrementReplyCount();
            replyRepository.save(parent);
        }

        BulletinReplyEntity entity = BulletinReplyEntity.builder()
                .threadId(threadId)
                .parentId(request.getParentId())
                .depth(depth)
                .authorId(userId)
                .body(request.getBody())
                .build();

        BulletinReplyEntity saved = replyRepository.save(entity);

        // スレッドの返信カウントを更新
        thread.incrementReplyCount();
        threadRepository.save(thread);

        log.info("返信作成: threadId={}, replyId={}", threadId, saved.getId());
        return bulletinMapper.toReplyResponse(saved);
    }

    /**
     * 返信を更新する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param replyId   返信ID
     * @param userId    操作者ID
     * @param request   更新リクエスト
     * @return 更新された返信レスポンス
     */
    @Transactional
    public ReplyResponse updateReply(ScopeType scopeType, Long scopeId, Long threadId, Long replyId, Long userId, UpdateReplyRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);
        BulletinReplyEntity entity = findReplyOrThrow(threadId, replyId);

        // 返信更新は投稿者本人のみ
        if (entity.getAuthorId() == null || !entity.getAuthorId().equals(userId)) {
            throw new BusinessException(BulletinErrorCode.NOT_AUTHOR);
        }

        // ロック中は返信編集不可（設計書 §5）
        if (Boolean.TRUE.equals(thread.getIsLocked())) {
            throw new BusinessException(BulletinErrorCode.THREAD_LOCKED);
        }

        entity.updateBody(request.getBody());
        BulletinReplyEntity saved = replyRepository.save(entity);
        log.info("返信更新: replyId={}", replyId);
        return bulletinMapper.toReplyResponse(saved);
    }

    /**
     * 返信を論理削除する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param replyId   返信ID
     */
    @Transactional
    public void deleteReply(ScopeType scopeType, Long scopeId, Long threadId, Long replyId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);
        BulletinReplyEntity entity = findReplyOrThrow(threadId, replyId);

        // 投稿者本人 or ADMIN/DEPUTY（DEPUTY は MANAGE_CONTENT 明示付与時のみ）
        boolean isOwner = entity.getAuthorId() != null && entity.getAuthorId().equals(userId);
        if (!isOwner) {
            accessGuard.requireManageContent(userId, scopeType, scopeId);
        }

        entity.softDelete();
        replyRepository.save(entity);

        // 親返信のカウントをデクリメント
        if (entity.getParentId() != null) {
            replyRepository.findById(entity.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                replyRepository.save(parent);
            });
        }

        // スレッドの返信カウントをデクリメント
        thread.decrementReplyCount();
        threadRepository.save(thread);

        log.info("返信削除: replyId={}, by={}", replyId, userId);

        // 他者コンテンツの削除のみ監査ログを記録（本人削除は記録不要）
        if (!isOwner) {
            recordReplyDeletionAudit(scopeType, scopeId, userId, entity.getAuthorId(), replyId);
        }
    }

    /**
     * 他者返信削除の監査ログを非同期記録する。
     */
    private void recordReplyDeletionAudit(ScopeType scopeType, Long scopeId,
                                          Long actorUserId, Long ownerUserId, Long replyId) {
        Long teamId = scopeType == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scopeType == ScopeType.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"source\":\"BULLETIN\",\"resource_id\":%d,\"owner_user_id\":%s,\"scope_type\":\"%s\",\"scope_id\":%d}",
                replyId, ownerUserId, scopeType.name(), scopeId);
        auditLogService.record(AuditEventType.BULLETIN_REPLY_DELETED.name(), actorUserId, ownerUserId,
                teamId, organizationId, null, null, null, metadata);
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — 返信 CRUD（グローバル経路）
    // ========================================================================

    /**
     * グローバル方式でスレッドの返信一覧を取得する（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、スレッドの {@code scopeType} を逆引きして認可経路を分岐する。
     * VILLAGE は村可視性認可（閲覧）、ORG/TEAM/PERSONAL は既存 {@link #listReplies} に委譲する。
     * トップレベル返信をページングし、子返信を同梱して返す。</p>
     *
     * @param threadId スレッド ID
     * @param userId   操作ユーザーID
     * @param pageable ページング情報
     * @return 返信レスポンスのページ（子返信付き）
     */
    public Page<ReplyResponse> listRepliesGlobal(Long threadId, Long userId, Pageable pageable) {
        BulletinThreadEntity thread = findThreadByIdOrThrow(threadId);
        if (thread.getScopeType() != ScopeType.VILLAGE) {
            return listReplies(thread.getScopeType(), thread.getScopeId(), threadId, userId, pageable);
        }
        villageBulletinAccessService.checkVillageBulletinViewAccess(thread.getScopeVillageId(), userId);
        Page<BulletinReplyEntity> page =
                replyRepository.findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(threadId, pageable);
        return page.map(this::toReplyWithChildren);
    }

    /**
     * グローバル方式で返信を作成する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は村メンバー + 投稿主体検証（{@link PostingIdentityService#validatePostingIdentity}）を行い、
     * ORG/TEAM/PERSONAL は既存 {@link #createReply} に委譲する。{@code parentId} はネスト返信時に指定する
     * （URL の {@code replyId} 由来）。ロック／アーカイブ中は投稿不可、ネスト深さは最大5階層（depth 0〜4）。</p>
     *
     * @param threadId スレッド ID
     * @param parentId 親返信 ID（null = スレッド直下）
     * @param userId   投稿者 ID
     * @param body     本文
     * @return 作成された返信レスポンス
     */
    @Transactional
    public ReplyResponse createReplyGlobal(Long threadId, Long parentId, Long userId, String body) {
        BulletinThreadEntity thread = findThreadByIdOrThrow(threadId);
        if (thread.getScopeType() != ScopeType.VILLAGE) {
            return createReply(thread.getScopeType(), thread.getScopeId(), threadId, userId,
                    new CreateReplyRequest(parentId, body));
        }
        // VILLAGE: 投稿主体検証（個人投稿 = USER/userId）。
        // validatePostingIdentity が村メンバー判定（非メンバーは NOT_MEMBER 403）を内包するため、
        // ここで重複の member チェックは行わない（既存 BulletinThreadService.createThread と同流儀）。
        postingIdentityService.validatePostingIdentity(
                userId, thread.getScopeVillageId(), VillageSubjectType.USER, userId);

        if (!thread.isWritable()) {
            throw new BusinessException(
                    thread.getIsLocked() ? BulletinErrorCode.THREAD_LOCKED : BulletinErrorCode.THREAD_ARCHIVED);
        }

        int depth = 0;
        if (parentId != null) {
            BulletinReplyEntity parent = replyRepository.findByIdAndThreadId(parentId, threadId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.PARENT_REPLY_MISMATCH));
            int parentDepth = parent.getDepth() != null ? parent.getDepth() : 0;
            depth = parentDepth + 1;
            if (depth > MAX_REPLY_DEPTH) {
                throw new BusinessException(BulletinErrorCode.REPLY_DEPTH_EXCEEDED);
            }
            parent.incrementReplyCount();
            replyRepository.save(parent);
        }

        BulletinReplyEntity entity = BulletinReplyEntity.builder()
                .threadId(threadId)
                .parentId(parentId)
                .depth(depth)
                .authorId(userId)
                .body(body)
                .build();
        BulletinReplyEntity saved = replyRepository.save(entity);

        thread.incrementReplyCount();
        threadRepository.save(thread);
        log.info("村返信作成: villageId={}, threadId={}, replyId={}",
                thread.getScopeVillageId(), threadId, saved.getId());
        return bulletinMapper.toReplyResponse(saved);
    }

    /**
     * グローバル方式でネスト返信を作成する（F17.1 村掲示板グローバル方式）。
     *
     * <p>FE は {@code POST /api/v1/bulletin/replies/{replyId}/replies} で親返信 ID のみを渡すため、
     * 親返信からスレッドを逆引きして {@link #createReplyGlobal} に委譲する。</p>
     *
     * @param parentReplyId 親返信 ID（URL 由来）
     * @param userId        投稿者 ID
     * @param body          本文
     * @return 作成された返信レスポンス
     */
    @Transactional
    public ReplyResponse createNestedReplyGlobal(Long parentReplyId, Long userId, String body) {
        BulletinReplyEntity parent = findReplyByIdOrThrow(parentReplyId);
        return createReplyGlobal(parent.getThreadId(), parentReplyId, userId, body);
    }

    /**
     * グローバル方式で返信を更新する（F17.1 村掲示板グローバル方式）。投稿者本人のみ。
     *
     * <p>{@code replyId} から返信→スレッドを逆引きし、VILLAGE は投稿者本人かつロック中はモデレーターのみ、
     * ORG/TEAM/PERSONAL は既存 {@link #updateReply} に委譲する。</p>
     *
     * @param replyId 返信 ID
     * @param userId  操作者 ID
     * @param body    新しい本文
     * @return 更新された返信レスポンス
     */
    @Transactional
    public ReplyResponse updateReplyGlobal(Long replyId, Long userId, String body) {
        BulletinReplyEntity reply = findReplyByIdOrThrow(replyId);
        BulletinThreadEntity thread = findThreadByIdOrThrow(reply.getThreadId());
        if (thread.getScopeType() != ScopeType.VILLAGE) {
            return updateReply(thread.getScopeType(), thread.getScopeId(), thread.getId(), replyId, userId,
                    new UpdateReplyRequest(body));
        }
        // VILLAGE: 投稿者本人のみ編集可
        if (reply.getAuthorId() == null || !reply.getAuthorId().equals(userId)) {
            throw new BusinessException(BulletinErrorCode.NOT_AUTHOR);
        }
        // ロック中はモデレーターのみ編集可（設計書 §5）
        if (Boolean.TRUE.equals(thread.getIsLocked())) {
            villageBulletinAccessService.checkVillageBulletinModerator(thread.getScopeVillageId(), userId);
        }
        reply.updateBody(body);
        BulletinReplyEntity saved = replyRepository.save(reply);
        log.info("村返信更新: villageId={}, replyId={}", thread.getScopeVillageId(), replyId);
        return bulletinMapper.toReplyResponse(saved);
    }

    /**
     * グローバル方式で返信を論理削除する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は投稿者本人 or 村モデレーター、ORG/TEAM/PERSONAL は既存 {@link #deleteReply} に委譲する。
     * 親返信・スレッドの返信カウントをデクリメントし、他者投稿削除時は監査ログを記録する。</p>
     *
     * @param replyId 返信 ID
     * @param userId  操作者 ID
     */
    @Transactional
    public void deleteReplyGlobal(Long replyId, Long userId) {
        BulletinReplyEntity reply = findReplyByIdOrThrow(replyId);
        BulletinThreadEntity thread = findThreadByIdOrThrow(reply.getThreadId());
        if (thread.getScopeType() != ScopeType.VILLAGE) {
            deleteReply(thread.getScopeType(), thread.getScopeId(), thread.getId(), replyId, userId);
            return;
        }
        boolean isOwner = reply.getAuthorId() != null && reply.getAuthorId().equals(userId);
        if (!isOwner) {
            villageBulletinAccessService.checkVillageBulletinModerator(thread.getScopeVillageId(), userId);
        }
        reply.softDelete();
        replyRepository.save(reply);

        if (reply.getParentId() != null) {
            replyRepository.findById(reply.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                replyRepository.save(parent);
            });
        }
        thread.decrementReplyCount();
        threadRepository.save(thread);
        log.info("村返信削除: villageId={}, replyId={}, by={}", thread.getScopeVillageId(), replyId, userId);

        if (!isOwner) {
            recordReplyDeletionAudit(thread.getScopeType(), thread.getScopeId(), userId, reply.getAuthorId(), replyId);
        }
    }

    /**
     * スレッドを ID のみで取得する（グローバル方式の逆引き）。存在しなければ 404。
     */
    private BulletinThreadEntity findThreadByIdOrThrow(Long threadId) {
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
    }

    /**
     * 返信を ID のみで取得する（グローバル方式の逆引き）。存在しなければ 404。
     */
    private BulletinReplyEntity findReplyByIdOrThrow(Long replyId) {
        return replyRepository.findById(replyId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.REPLY_NOT_FOUND));
    }

    /**
     * 返信エンティティを子返信付きレスポンスに変換する。
     */
    private ReplyResponse toReplyWithChildren(BulletinReplyEntity entity) {
        List<BulletinReplyEntity> children = replyRepository.findByParentIdOrderByCreatedAtAsc(entity.getId());
        List<ReplyResponse> childResponses = children.stream()
                .map(child -> bulletinMapper.toReplyResponse(child))
                .toList();
        return bulletinMapper.toReplyResponse(entity, childResponses);
    }

    /**
     * 返信を取得する。存在しない場合は例外をスローする。
     */
    private BulletinReplyEntity findReplyOrThrow(Long threadId, Long replyId) {
        return replyRepository.findByIdAndThreadId(replyId, threadId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.REPLY_NOT_FOUND));
    }
}
