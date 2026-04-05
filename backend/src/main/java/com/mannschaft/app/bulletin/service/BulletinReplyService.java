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

    /**
     * スレッドの返信一覧をページング取得する（トップレベルのみ）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param pageable  ページング情報
     * @return 返信レスポンスのページ（子返信付き）
     */
    public Page<ReplyResponse> listReplies(ScopeType scopeType, Long scopeId, Long threadId, Pageable pageable) {
        threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        Page<BulletinReplyEntity> page =
                replyRepository.findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(threadId, pageable);
        return page.map(this::toReplyWithChildren);
    }

    /**
     * 返信を作成する。
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
        threadService.findThreadOrThrow(scopeType, scopeId, threadId);
        BulletinReplyEntity entity = findReplyOrThrow(threadId, replyId);

        if (!entity.getAuthorId().equals(userId)) {
            throw new BusinessException(BulletinErrorCode.NOT_AUTHOR);
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
    public void deleteReply(ScopeType scopeType, Long scopeId, Long threadId, Long replyId) {
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);
        BulletinReplyEntity entity = findReplyOrThrow(threadId, replyId);

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

        log.info("返信削除: replyId={}", replyId);
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
