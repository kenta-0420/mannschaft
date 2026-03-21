package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 掲示板既読ステータスサービス。既読マーク・既読者一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinReadStatusService {

    private final BulletinReadStatusRepository readStatusRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinThreadService threadService;
    private final BulletinMapper bulletinMapper;

    /**
     * スレッドを既読にする。既に既読の場合は何もしない。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    ユーザーID
     */
    @Transactional
    public void markAsRead(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        if (readStatusRepository.existsByThreadIdAndUserId(threadId, userId)) {
            return;
        }

        BulletinReadStatusEntity entity = BulletinReadStatusEntity.builder()
                .threadId(threadId)
                .userId(userId)
                .build();
        readStatusRepository.save(entity);

        thread.incrementReadCount();
        threadRepository.save(thread);

        log.info("既読マーク: threadId={}, userId={}", threadId, userId);
    }

    /**
     * スレッドの既読者一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @return 既読ステータスレスポンスリスト
     */
    public List<ReadStatusResponse> listReadUsers(ScopeType scopeType, Long scopeId, Long threadId) {
        threadService.findThreadOrThrow(scopeType, scopeId, threadId);
        List<BulletinReadStatusEntity> readStatuses = readStatusRepository.findByThreadIdOrderByReadAtDesc(threadId);
        return bulletinMapper.toReadStatusResponseList(readStatuses);
    }

    /**
     * スレッドの既読数を取得する。
     *
     * @param threadId スレッドID
     * @return 既読数
     */
    public long getReadCount(Long threadId) {
        return readStatusRepository.countByThreadId(threadId);
    }
}
