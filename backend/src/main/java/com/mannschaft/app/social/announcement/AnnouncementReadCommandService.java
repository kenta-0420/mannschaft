package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * お知らせ既読の<b>判定</b>と書き込みの順序づけ（単件・read-all・read-batch。ALIC-1 §2.4・§2.5）。
 *
 * <p><b>このクラスはトランザクションを持たない</b>。「自分に見えているお知らせなら既読にしてよい」の判定
 * （帰属・可視性・宛先資格・課金。キャッシュなしの役割解決を含む）を tx の外で済ませてから、social の
 * Repository だけに到達する書き込み（{@link AnnouncementReadService}）を呼ぶ。判定を tx の中に置くと、キャッシュなしの
 * 役割解決が他ドメインの Repository に到達して凍結ストアに新しい組を作るため。既読の書き込みメソッド
 * （{@code AnnouncementFeedService.markAsRead}・{@code AnnouncementReadService.markAsRead}・{@code markFeedIdsAsRead}）を
 * 呼べるのは本クラスだけである（ArchUnit F03）。</p>
 *
 * <ul>
 *   <li><b>単件</b> — 告知の帰属・宛先資格（Qd / Qa）・課金 HIDDEN を確認してから 1 件を既読にする。</li>
 *   <li><b>read-all（直属範囲）</b> — チャンク（500 件）ごとに、直前に閲覧者の役割をキャッシュなしで解決し直し、
 *       そのチャンクの未読をその時点の資格と課金で評価して 1 tx で書く。資格を失った時点以降のチャンクでは
 *       不可視になった項目に既読行を作らない。終端判定は課金で除外する<b>前</b>の取得件数で行う。
 *       上限で走査が終わらなければ位置をサーバ側だけで保持し、次の要求が続きから走査する（{@link AnnouncementReadAllCursorStore}）。</li>
 *   <li><b>read-batch（組織）</b> — 表示中の ID を 1 件ずつ宛先資格で判定し、通ったものだけを 1 tx で書く。
 *       通らない ID は黙って飛ばし件数だけ返す。評価の障害は 500 で 1 行も書かない。</li>
 * </ul>
 *
 * <p>判定から書き込みまでの間に資格を失うと、直前に判定した 1 単位（単件 1 件・read-batch 最大 50 件・read-all の 1 チャンク）の
 * 既読行ができうる（M13。既読行は本文も宛先も含まず、何かを開く権限にもならない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementReadCommandService {

    /** read-batch で 1 回に受け付ける最大件数。 */
    public static final int READ_BATCH_MAX = 50;

    private final AnnouncementFeedRepository feedRepository;
    private final AnnouncementFeedQueryRepository feedQueryRepository;
    private final AnnouncementFeedService feedService;
    private final AnnouncementReadService readService;
    private final OrgAnnouncementAudienceAccess audienceAccess;
    private final RoleResolver roleResolver;
    private final PaymentGateService paymentGateService;
    private final AnnouncementReadAllCursorStore cursorStore;
    @Qualifier("wallClock")
    private final Clock clock;

    // ═════════════════════════════════════════════════════════════
    // 単件
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。見えない・存在しない・越境・削除済み・期限切れは同一の
     * {@link AnnouncementErrorCode#ANNOUNCE_001}（存在秘匿）。
     */
    public void markAsRead(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        AnnouncementFeedEntity feed = feedRepository.findById(announcementId)
                .filter(f -> f.getScopeType() == scopeType && scopeId.equals(f.getScopeId()))
                .filter(this::isLive)
                .orElseThrow(AnnouncementReadCommandService::notFound);
        if (!isReadable(feed, userId)) {
            throw notFound();
        }
        feedService.markAsRead(scopeType, scopeId, announcementId, userId);
    }

    private boolean isReadable(AnnouncementFeedEntity feed, Long userId) {
        ViewerRole role;
        if (feed.getScopeType() == AnnouncementScopeType.ORGANIZATION) {
            OrgAnnouncementAudienceAccess.Access access = audienceAccess.evaluate(feed, userId);
            if (!access.allowed()) {
                return false;
            }
            role = access.directRole();
        } else if (feed.getScopeType() == AnnouncementScopeType.TEAM) {
            role = roleResolver.resolveViewerRoleForPreview(userId, AnnouncementScopeType.TEAM.name(), feed.getScopeId());
            if (!AnnouncementVisibility.isVisibleTo(feed.getVisibility(), role.name())) {
                return false;
            }
        } else {
            // COMMITTEE / ADVERTISER_AD 等は既読 EP の入口を持たない。将来入口が増えても無検証で素通りしない（fail-closed）。
            return false;
        }
        if (role == ViewerRole.ADMIN || role == ViewerRole.SYSTEM_ADMIN) {
            return true;
        }
        GateCheckResponse gate = paymentGateService.checkAccess(
                ContentGateType.ANNOUNCEMENT, feed.getId(), userId, targetOf(feed));
        // null は評価不能として fail-closed にする。
        return gate != null && (gate.isAccessible() || !gate.isTitleHidden());
    }

    // ═════════════════════════════════════════════════════════════
    // read-batch（組織）
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織の表示中のお知らせを ID 指定でまとめて既読にする。
     *
     * <p>重複は除いてから評価する。存在しない・パスの組織と違う・宛先外・可視性不足・期限切れ・元削除済み・課金 HIDDEN は
     * 黙って除外する。評価の障害（DB 例外・役割解決失敗・課金結果 null/例外）はそのまま伝播して 500 とし、
     * 判定を全部終えてから書くので 1 行も書かない。</p>
     *
     * @return 判定を通った ID のうち、書き込み前に未読だった件数（どの ID が無効かは返さない）
     */
    public int markBatch(Long orgId, Collection<Long> feedIds, Long userId) {
        List<Long> ids = feedIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        List<AnnouncementFeedEntity> feeds = feedRepository.findAllById(ids).stream()
                .filter(f -> f.getScopeType() == AnnouncementScopeType.ORGANIZATION && orgId.equals(f.getScopeId()))
                .filter(this::isLive)
                .toList();
        if (feeds.isEmpty()) {
            return 0;
        }
        Map<Long, OrgAnnouncementAudienceAccess.Access> access = audienceAccess.evaluateAll(feeds, userId);
        List<AnnouncementFeedEntity> allowed = feeds.stream()
                .filter(f -> access.get(f.getId()).allowed()).toList();
        if (allowed.isEmpty()) {
            return 0;
        }
        Map<Long, ContentGateTarget> targets = new HashMap<>();
        allowed.forEach(f -> targets.put(f.getId(), targetOf(f)));
        Map<Long, GateCheckResponse> gates = Objects.requireNonNull(
                paymentGateService.checkAccessBatchForPreview(ContentGateType.ANNOUNCEMENT,
                        allowed.stream().map(AnnouncementFeedEntity::getId).toList(), userId, targets),
                "一括課金評価の結果が null です");
        List<Long> readable = new ArrayList<>();
        for (AnnouncementFeedEntity feed : allowed) {
            ViewerRole role = access.get(feed.getId()).directRole();
            GateCheckResponse gate = gates.get(feed.getId());
            if (role == ViewerRole.ADMIN || role == ViewerRole.SYSTEM_ADMIN
                    || (gate != null && (gate.isAccessible() || !gate.isTitleHidden()))) {
                readable.add(feed.getId());
            }
        }
        return readService.markFeedIdsAsRead(userId, readable);
    }

    // ═════════════════════════════════════════════════════════════
    // read-all（直属範囲）
    // ═════════════════════════════════════════════════════════════

    /**
     * スコープ内の全お知らせのうち、閲覧者に可視な未読を既読にする（直属範囲。組織スコープでも宛先資格 Qa は加えない）。
     *
     * <p>1 リクエストの上限は 500 件 × 20 チャンクで、上限に達して走査が終わらなければ {@code hasMoreUnread=true} とし、
     * 最後に取得した feed ID をサーバ側に保存して次の要求が続きから走査する。取得した ID は応答に出さない。</p>
     */
    public AnnouncementReadService.MarkAllReadOutcome markAllAsRead(AnnouncementScopeType scopeType, Long scopeId,
                                                                    Long userId) {
        if (scopeType != AnnouncementScopeType.TEAM && scopeType != AnnouncementScopeType.ORGANIZATION) {
            // fail-closed: 入口を持たないスコープでは 1 件も既読にしない
            return AnnouncementReadService.MarkAllReadOutcome.completed(0);
        }
        Long lastSeenId = cursorStore.load(userId, scopeType, scopeId);
        int markedCount = 0;
        Set<String> allowedVisibilities = Set.of();
        for (int batch = 0; batch < AnnouncementReadService.MARK_ALL_MAX_BATCHES; batch++) {
            // 各チャンクの直前に、閲覧者の役割をキャッシュなしで解決し直す（資格を失った時点以降は不可視になる）
            allowedVisibilities = allowedVisibilities(scopeType, scopeId, userId);
            List<Long> fetched = feedQueryRepository.findUnreadIdsByScope(scopeType, scopeId, allowedVisibilities,
                    userId, lastSeenId, AnnouncementReadService.MARK_ALL_BATCH_SIZE);
            if (fetched.isEmpty()) {
                return completed(scopeType, scopeId, userId, markedCount);
            }
            // 終端判定と次チャンクの起点は、課金で除外する前の取得結果で決める（HIDDEN が混じっても後続を取り残さない）
            Long fetchedLastId = fetched.get(fetched.size() - 1);
            List<Long> readable = filterByGate(scopeType, scopeId, userId, fetched);
            if (!readable.isEmpty()) {
                markedCount += readService.markFeedIdsAsRead(userId, readable);
            }
            lastSeenId = fetchedLastId;
            if (fetched.size() < AnnouncementReadService.MARK_ALL_BATCH_SIZE) {
                return completed(scopeType, scopeId, userId, markedCount);
            }
        }

        // 防御上限に到達。「本当に残っているのか」を 1 件だけ覗いて裏を取る（上限ちょうどで尽きていた場合に誤報しない）。
        boolean hasMoreUnread = !feedQueryRepository.findUnreadIdsByScope(
                scopeType, scopeId, allowedVisibilities, userId, lastSeenId, 1).isEmpty();
        if (hasMoreUnread) {
            // 症状を隠さず WARN で記録し、続きの位置をサーバ側に保持する（残余は次回の一括既読が続きから処理する）。
            log.warn("全件既読マークが1リクエストの上限に到達 scopeType={}, scopeId={}, userId={}, marked={} "
                            + "（未読が残っている・上限={}件）",
                    scopeType, scopeId, userId, markedCount,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE * AnnouncementReadService.MARK_ALL_MAX_BATCHES);
            cursorStore.save(userId, scopeType, scopeId, lastSeenId);
            return new AnnouncementReadService.MarkAllReadOutcome(markedCount, true);
        }
        return completed(scopeType, scopeId, userId, markedCount);
    }

    private AnnouncementReadService.MarkAllReadOutcome completed(AnnouncementScopeType scopeType, Long scopeId,
                                                                 Long userId, int markedCount) {
        cursorStore.clear(userId, scopeType, scopeId);
        log.debug("全件既読マーク完了 scopeType={}, scopeId={}, userId={}, count={}",
                scopeType, scopeId, userId, markedCount);
        return AnnouncementReadService.MarkAllReadOutcome.completed(markedCount);
    }

    /** 閲覧者がスコープで見られる visibility の集合（キャッシュなしの役割から。一覧側と同じ正準マッピング）。 */
    private Set<String> allowedVisibilities(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        ViewerRole role = roleResolver.resolveViewerRoleForPreview(userId, scopeType.name(), scopeId);
        return AnnouncementVisibility.allowedFor(role.name());
    }

    /** チャンクの課金を一括評価し、HIDDEN（未充足かつタイトル非表示・評価対象外）を除いた ID を返す。 */
    private List<Long> filterByGate(AnnouncementScopeType scopeType, Long scopeId, Long userId, List<Long> ids) {
        Map<Long, ContentGateTarget> targets = new LinkedHashMap<>();
        for (Long id : ids) {
            targets.put(id, scopeType == AnnouncementScopeType.TEAM
                    ? new ContentGateTarget(id, scopeId, null) : new ContentGateTarget(id, null, scopeId));
        }
        Map<Long, GateCheckResponse> gates = Objects.requireNonNull(
                paymentGateService.checkAccessBatchForPreview(ContentGateType.ANNOUNCEMENT, ids, userId, targets),
                "一括課金評価の結果が null です");
        return ids.stream().filter(id -> {
            GateCheckResponse gate = gates.get(id);
            return gate != null && !gate.isTitleHidden();
        }).collect(Collectors.toList());
    }

    // ═════════════════════════════════════════════════════════════
    // 共通
    // ═════════════════════════════════════════════════════════════

    /** 元コンテンツが削除されておらず、期限内か（一覧クエリの WHERE 句と同じ条件）。 */
    private boolean isLive(AnnouncementFeedEntity feed) {
        return feed.getSourceDeletedAt() == null
                && (feed.getExpiresAt() == null || feed.getExpiresAt().isAfter(LocalDateTime.now(clock)));
    }

    private static ContentGateTarget targetOf(AnnouncementFeedEntity feed) {
        if (feed == null || feed.getId() == null || feed.getScopeType() == null || feed.getScopeId() == null) {
            return null;
        }
        return feed.getScopeType() == AnnouncementScopeType.TEAM
                ? new ContentGateTarget(feed.getId(), feed.getScopeId(), null)
                : feed.getScopeType() == AnnouncementScopeType.ORGANIZATION
                    ? new ContentGateTarget(feed.getId(), null, feed.getScopeId()) : null;
    }

    private static BusinessException notFound() {
        return new BusinessException(AnnouncementErrorCode.ANNOUNCE_001);
    }
}
