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

    private final BulletinReplyRepository replyRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinThreadService threadService;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    private final AuditLogService auditLogService;

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

        // 親返信の存在確認
        if (request.getParentId() != null) {
            BulletinReplyEntity parent = replyRepository.findByIdAndThreadId(request.getParentId(), threadId)
                    .orElseThrow(() -> new BusinessException(BulletinErrorCode.PARENT_REPLY_MISMATCH));
            parent.incrementReplyCount();
            replyRepository.save(parent);
        }

        BulletinReplyEntity entity = BulletinReplyEntity.builder()
                .threadId(threadId)
                .parentId(request.getParentId())
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
