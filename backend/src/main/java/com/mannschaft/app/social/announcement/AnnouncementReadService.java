package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
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
 *
 * <p>
 * <b>認可モデル（認可根治「裏目付」C-social）</b>:
 * 既読系 EP は「呼び出し元スコープのメンバー以上」であることを入口で検証し、
 * 単件既読ではさらに「対象お知らせが当該スコープに帰属すること」を照合する。
 * どちらか一方でも欠けると、認証済みでありさえすれば無関係なスコープの URL 経由で
 * 他テナントのお知らせに既読行を作れてしまう（書き込み副作用 + 実在オラクル）。
 * </p>
 *
 * <p>
 * <b>存在秘匿</b>: 帰属しないお知らせと真に存在しないお知らせは、いずれも
 * {@link AnnouncementErrorCode#ANNOUNCE_001} に畳み込んで区別できないようにする
 * （応答差分から ID の実在が漏れないようにするため）。
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
    private final AccessControlService accessControlService;

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
     * <p>
     * <b>認可</b>: 呼び出し元スコープのメンバー以上であること（{@link #assertScopeMember}）と、
     * 対象お知らせが当該スコープに帰属すること（{@link #assertAnnouncementInScope}）を検証する。
     * スコープは URL のパス変数（{@code teamId} / {@code orgId}）から Controller が渡す。
     * </p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID（teams.id または organizations.id）
     * @param announcementId お知らせフィード ID
     * @param userId         ユーザー ID
     */
    @Transactional
    public void markAsRead(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        // 1. 入口の認可: 当該スコープのメンバー以上か（非メンバーは 403 COMMON_002）
        assertScopeMember(scopeType, scopeId, userId);

        // 2. 帰属検証: 対象お知らせが当該スコープのものか（不在・越境はいずれも ANNOUNCE_001 に畳み込む）
        assertAnnouncementInScope(scopeType, scopeId, announcementId);

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
     * <p>
     * <b>認可</b>: 呼び出し元スコープのメンバー以上であることを入口で検証する
     * （{@link #assertScopeMember}）。これが無いと非メンバーが他テナントのスコープを指定して
     * 当該スコープ配下の全お知らせに自分の既読行を一括生成できてしまう（DB 汚染）。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param userId    ユーザー ID
     */
    @Transactional
    public void markAllAsRead(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        // 入口の認可: 当該スコープのメンバー以上か（非メンバーは 403 COMMON_002）
        assertScopeMember(scopeType, scopeId, userId);

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
    // 認可ヘルパー（認可根治「裏目付」C-social）
    // ═════════════════════════════════════════════════════════════

    /**
     * 呼び出し元が当該スコープのメンバー以上であることを検証する。違反時は 403（COMMON_002）。
     *
     * <p>ORGANIZATION スコープでは、組織直属メンバーだけでなく配下チームのみに所属する
     * メンバー・応援者も対象に含める（{@code includeSupporters = true}）。組織告知は配下チームへ
     * 配信されるため、直接所属のみを見る素の {@code isMember} で判定すると配下メンバーが
     * 自分宛のお知らせを既読にできなくなる（配信＝受信権）。TEAM スコープでは
     * {@code AccessControlService#isMemberOrDescendant} は素の {@code isMember} と等価。</p>
     *
     * <p>COMMITTEE / ADVERTISER_AD は既読 EP の入口を持たない（Controller は TEAM /
     * ORGANIZATION のみ渡す）。将来入口が増えたときに無検証で素通りしないよう fail-closed で拒否する。</p>
     */
    private void assertScopeMember(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        switch (scopeType) {
            case TEAM, ORGANIZATION ->
                    accessControlService.checkMembershipOrDescendant(userId, scopeId, scopeType.name(), true);
            default -> throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_002);
        }
    }

    /**
     * 対象お知らせが当該スコープに帰属することを検証する。違反時は 404 相当（ANNOUNCE_001）。
     *
     * <p>帰属しない既存 ID も、そもそも存在しない ID も同一の {@link AnnouncementErrorCode#ANNOUNCE_001}
     * に畳み込むことで、応答差分から ID の実在が漏れないようにする（実在オラクル封じ）。
     * 同ドメインの {@code AnnouncementRangeTemplateService#update/delete} と同じ
     * 「{@code findById(...).filter(スコープ一致).orElseThrow(...)}」の型に揃えている。</p>
     */
    private void assertAnnouncementInScope(AnnouncementScopeType scopeType, Long scopeId, Long announcementId) {
        feedRepository.findById(announcementId)
                .filter(feed -> feed.getScopeType() == scopeType && scopeId.equals(feed.getScopeId()))
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_001));
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
