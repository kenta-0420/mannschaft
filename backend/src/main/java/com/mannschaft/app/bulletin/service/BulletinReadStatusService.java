package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
    private final BulletinAccessGuard accessGuard;

    /**
     * スレッドを既読にする。既に既読の場合は何もしない。所属メンバーのみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    ユーザーID
     */
    @Transactional
    public void markAsRead(ScopeType scopeType, Long scopeId, Long threadId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
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
     * スレッドの既読者一覧を取得する。所属メンバーのみ。
     *
     * <p>設計書 §6（既読プライバシー）に従い、{@code read_tracking_mode} で個人情報の返却を制御する:</p>
     * <ul>
     *   <li>{@code INDIVIDUAL}（= 設計書 SHOW_READERS）: 既読者の配列をフル返却</li>
     *   <li>{@code COUNT_ONLY}（および NONE 相当）: 既読者の配列は返さず、件数のみを表示用に許容
     *       （本メソッドは空リストを返す。件数は {@link #getReadCount(Long)} で取得）</li>
     * </ul>
     *
     * <p>{@code filter=unread}（未読者一覧）は ADMIN のみ許可する（CRITICAL スレッドの確認漏れチェック用）。
     * 非 ADMIN が unread を指定した場合は 403。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param threadId  スレッドID
     * @param userId    操作ユーザーID
     * @param filter    フィルタ（{@code "unread"} 指定時は ADMIN のみ）
     * @return 既読ステータスレスポンスリスト（プライバシーモードにより空配列の場合あり）
     */
    public List<ReadStatusResponse> listReadUsers(ScopeType scopeType, Long scopeId, Long threadId,
                                                  Long userId, String filter) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinThreadEntity thread = threadService.findThreadOrThrow(scopeType, scopeId, threadId);

        // filter=unread は ADMIN のみ
        boolean unreadFilter = "unread".equalsIgnoreCase(filter);
        if (unreadFilter && !accessGuard.isAdmin(userId, scopeType, scopeId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 既読プライバシー: INDIVIDUAL（SHOW_READERS）のみ readers をフル返却。
        // COUNT_ONLY / NONE は個人情報を返さない（件数は read_count / getReadCount で取得）。
        // ADMIN は確認漏れチェックの責務があるため、モードに関わらず参照可能とする。
        boolean canSeeReaders = thread.getReadTrackingMode() == ReadTrackingMode.INDIVIDUAL
                || accessGuard.isAdmin(userId, scopeType, scopeId);
        if (!canSeeReaders) {
            return List.of();
        }

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
