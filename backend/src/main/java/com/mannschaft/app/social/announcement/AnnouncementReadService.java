package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * お知らせ既読管理サービス（F02.6）。
 *
 * <p>
 * お知らせウィジェットの既読マーク（単件・全件）を担う。
 * 既読状態のバッチ取得ヘルパー（N+1 防止）も提供する。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementReadService {

    private final AnnouncementFeedRepository feedRepository;
    private final AnnouncementReadStatusRepository readStatusRepository;
    private final ProxyInputContext proxyInputContext;
    private final AnnouncementCreationService creationService;

    // ═════════════════════════════════════════════════════════════
    // 2.5 既読マーク（単件）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * <p>
     * 既に既読レコードが存在する場合は何もしない。
     * </p>
     *
     * @param announcementId お知らせフィード ID
     * @param userId         ユーザー ID
     */
    @Transactional
    public void markAsRead(Long announcementId, Long userId) {
        // お知らせ存在確認
        if (!feedRepository.existsById(announcementId)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_001);
        }

        // 冪等: 既読済みなら何もしない
        boolean alreadyRead = readStatusRepository
                .findByAnnouncementFeedIdAndUserId(announcementId, userId)
                .isPresent();
        if (alreadyRead) {
            return;
        }

        AnnouncementReadStatusEntity status = AnnouncementReadStatusEntity.builder()
                .announcementFeedId(announcementId)
                .userId(userId)
                .build();
        status = readStatusRepository.save(status);

        // 代理確認の場合: proxy_input_records を作成し、is_proxy_confirmed フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = creationService.buildAndSaveAnnouncementProxyRecord(
                    "ANNOUNCEMENT_READ", announcementId);
            readStatusRepository.save(status.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.debug("既読マーク完了 announcementId={}, userId={}", announcementId, userId);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.6 全件既読
    // ═════════════════════════════════════════════════════════════

    /**
     * スコープ内の全お知らせを既読にする。
     *
     * <p>
     * 既読済みのものは除外し、未読のものだけを既読登録する。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param userId    ユーザー ID
     */
    @Transactional
    public void markAllAsRead(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        // スコープ内の有効なフィード一覧を取得
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<AnnouncementFeedEntity> feeds = feedRepository
                .findByScopeTypeAndScopeIdAndSourceDeletedAtIsNull(scopeType, scopeId, sort);

        if (feeds.isEmpty()) {
            return;
        }

        List<Long> feedIds = feeds.stream().map(AnnouncementFeedEntity::getId).toList();

        // 既読済みのフィード ID セットを取得
        Set<Long> alreadyReadFeedIds = fetchReadFeedIds(userId, feedIds);

        // 未読のものだけ既読登録
        List<AnnouncementReadStatusEntity> newReadStatuses = feedIds.stream()
                .filter(feedId -> !alreadyReadFeedIds.contains(feedId))
                .<AnnouncementReadStatusEntity>map(feedId -> AnnouncementReadStatusEntity.builder()
                        .announcementFeedId(feedId)
                        .userId(userId)
                        .build())
                .toList();

        if (!newReadStatuses.isEmpty()) {
            readStatusRepository.saveAll(newReadStatuses);
            log.debug("全件既読マーク完了 scopeType={}, scopeId={}, userId={}, count={}",
                    scopeType, scopeId, userId, newReadStatuses.size());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: 既読 ID セット取得（N+1 防止）
    // ═════════════════════════════════════════════════════════════

    /**
     * ユーザーが既読しているフィード ID のセットをバッチ取得する（N+1 防止）。
     *
     * @param userId  ユーザー ID
     * @param feedIds フィード ID リスト
     * @return 既読済みフィード ID セット
     */
    public Set<Long> fetchReadFeedIds(Long userId, List<Long> feedIds) {
        if (feedIds.isEmpty()) {
            return Set.of();
        }
        // AnnouncementReadStatusRepository から既読レコードをバッチ取得
        return feedIds.stream()
                .filter(feedId -> readStatusRepository
                        .findByAnnouncementFeedIdAndUserId(feedId, userId)
                        .isPresent())
                .collect(Collectors.toSet());
    }
}
