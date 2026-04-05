package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 掲示板スレッドサービス。スレッドのCRUD・検索・状態管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinThreadService {

    private final BulletinThreadRepository threadRepository;
    private final BulletinCategoryService categoryService;
    private final BulletinMapper bulletinMapper;

    /**
     * スコープのスレッド一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param pageable  ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> listThreads(ScopeType scopeType, Long scopeId, Pageable pageable) {
        Page<BulletinThreadEntity> page =
                threadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(scopeType, scopeId, pageable);
        return page.map(bulletinMapper::toThreadResponse);
    }

    /**
     * カテゴリ指定でスレッド一覧をページング取得する。
     *
     * @param categoryId カテゴリID
     * @param pageable   ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> listThreadsByCategory(Long categoryId, Pageable pageable) {
        Page<BulletinThreadEntity> page =
                threadRepository.findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(categoryId, pageable);
        return page.map(bulletinMapper::toThreadResponse);
    }

    /**
     * スレッド詳細を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @return スレッドレスポンス
     */
    public ThreadResponse getThread(ScopeType scopeType, Long scopeId, Long threadId) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        return bulletinMapper.toThreadResponse(entity);
    }

    /**
     * 全文検索でスレッドを検索する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param keyword   検索キーワード
     * @param pageable  ページング情報
     * @return スレッドレスポンスのページ
     */
    public Page<ThreadResponse> searchThreads(ScopeType scopeType, Long scopeId, String keyword, Pageable pageable) {
        Page<BulletinThreadEntity> page =
                threadRepository.searchByKeyword(scopeType.name(), scopeId, keyword, pageable);
        return page.map(bulletinMapper::toThreadResponse);
    }

    /**
     * スレッドを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse createThread(ScopeType scopeType, Long scopeId, Long userId, CreateThreadRequest request) {
        // カテゴリの存在確認
        categoryService.findCategoryOrThrow(scopeType, scopeId, request.getCategoryId());

        Priority priority = request.getPriority() != null
                ? Priority.valueOf(request.getPriority()) : Priority.INFO;
        ReadTrackingMode trackingMode = request.getReadTrackingMode() != null
                ? ReadTrackingMode.valueOf(request.getReadTrackingMode()) : ReadTrackingMode.COUNT_ONLY;

        BulletinThreadEntity entity = BulletinThreadEntity.builder()
                .categoryId(request.getCategoryId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .authorId(userId)
                .title(request.getTitle())
                .body(request.getBody())
                .priority(priority)
                .readTrackingMode(trackingMode)
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .build();

        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッド作成: scopeType={}, scopeId={}, threadId={}", scopeType, scopeId, saved.getId());
        return bulletinMapper.toThreadResponse(saved);
    }

    /**
     * スレッドを更新する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作者ID
     * @param request   更新リクエスト
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse updateThread(ScopeType scopeType, Long scopeId, Long threadId, Long userId, UpdateThreadRequest request) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);

        if (!entity.getAuthorId().equals(userId)) {
            throw new BusinessException(BulletinErrorCode.NOT_AUTHOR);
        }

        Priority priority = request.getPriority() != null
                ? Priority.valueOf(request.getPriority()) : entity.getPriority();

        entity.update(request.getTitle(), request.getBody(), priority);
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッド更新: threadId={}", threadId);
        return bulletinMapper.toThreadResponse(saved);
    }

    /**
     * スレッドを論理削除する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     */
    @Transactional
    public void deleteThread(ScopeType scopeType, Long scopeId, Long threadId) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.softDelete();
        threadRepository.save(entity);
        log.info("スレッド削除: threadId={}", threadId);
    }

    /**
     * ピン留めを切り替える。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse togglePin(ScopeType scopeType, Long scopeId, Long threadId) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.togglePin();
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドピン切替: threadId={}, isPinned={}", threadId, saved.getIsPinned());
        return bulletinMapper.toThreadResponse(saved);
    }

    /**
     * ロックを切り替える。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse toggleLock(ScopeType scopeType, Long scopeId, Long threadId) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.toggleLock();
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドロック切替: threadId={}, isLocked={}", threadId, saved.getIsLocked());
        return bulletinMapper.toThreadResponse(saved);
    }

    /**
     * アーカイブする。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @return 更新されたスレッドレスポンス
     */
    @Transactional
    public ThreadResponse archive(ScopeType scopeType, Long scopeId, Long threadId) {
        BulletinThreadEntity entity = findThreadOrThrow(scopeType, scopeId, threadId);
        entity.archive();
        BulletinThreadEntity saved = threadRepository.save(entity);
        log.info("スレッドアーカイブ: threadId={}", threadId);
        return bulletinMapper.toThreadResponse(saved);
    }

    /**
     * スレッドエンティティを取得する。存在しない場合は例外をスローする。
     */
    BulletinThreadEntity findThreadOrThrow(ScopeType scopeType, Long scopeId, Long threadId) {
        return threadRepository.findByIdAndScopeTypeAndScopeId(threadId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.THREAD_NOT_FOUND));
    }
}
