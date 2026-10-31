package com.mannschaft.app.social.announcement;

import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.ContentAccessState;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 組織の告知一覧（{@code GET /api/v1/organizations/{orgId}/announcements}）の取りまとめ役（CMP-261011-0035）。
 * <b>このクラスはトランザクションを持たない</b>（{@code @Transactional} を付けない）。
 *
 * <p>一覧は「その閲覧者がプレビューで読める告知だけ」を返す（一覧と本文の可否を一致させる）。キャッシュなしの役割解決を含む
 * 資格判定を tx の外で行うため、次の順に取りまとめる（ArchUnit F04）。</p>
 * <ol>
 *   <li>行の取得（{@link AnnouncementFeedQueryRepository#findByScope}。可視性の前絞りは最も広い集合）</li>
 *   <li>資格判定（{@link OrgAnnouncementFeedReadability}。告知の資格 Qd／Qa と元コンテンツの可視性。取得行をまとめて判定）</li>
 *   <li>課金ゲート（HIDDEN は管理者でも除外）と既読状態の付与</li>
 * </ol>
 * <p>ゲートや資格判定で落ちて足りなくなった分は、このループでカーソルを進めて補充取得する。</p>
 *
 * <p>公開 API の型は {@link AnnouncementFeedService.AnnouncementFeedResult} だけで、エンティティを引数・戻り値に出さない。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgAnnouncementFeedQueryService {

    /** デフォルト取得件数 */
    private static final int DEFAULT_LIMIT = 20;

    /** 最大取得件数 */
    private static final int MAX_LIMIT = 50;

    /** 1 回の取得で読む行数の下限（件数が小さくても判定で落ちる行を補充できるよう、まとめて読む）。 */
    static final int SCAN_BATCH = 50;

    /** 1 リクエストで走査する行数の上限（防御上限）。到達しても続きは次のリクエストで再開できる。 */
    static final int MAX_SCANNED_ROWS = 2000;

    private final AnnouncementFeedQueryRepository feedQueryRepository;
    private final AnnouncementReadService readService;
    private final OrgAnnouncementFeedReadability readability;
    private final PaymentGateService paymentGateService;

    /**
     * 組織の告知一覧をカーソルページングで取得する。
     *
     * @param orgId          組織 ID
     * @param requestUserId  閲覧者
     * @param viewerRoleName 組織での閲覧者の実ロール名（課金ゲートの管理者バイパスの判定に使う）
     * @param cursor         カーソル（null は先頭から）
     * @param limit          取得件数（0 以下は既定、上限超は補正）
     */
    public AnnouncementFeedService.AnnouncementFeedResult getFeed(
            Long orgId, Long requestUserId, String viewerRoleName, Long cursor, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        // 最終的な可否は資格判定が決める。宛先チームの委任資格で読める行を取りこぼさないよう、前絞りは最も広い集合で取る。
        Set<String> allowedVisibilities = AnnouncementVisibility.allowedFor("MEMBER");
        boolean adminBypass = "ADMIN".equalsIgnoreCase(viewerRoleName)
                || "SYSTEM_ADMIN".equalsIgnoreCase(viewerRoleName);

        Map<Long, GateCheckResponse> gates = new LinkedHashMap<>();
        List<AnnouncementFeedEntity> gatedRows = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        // 並び（ピン留め→新着→ID）と一致するキーセットで読み進める。API のカーソルは直前の最後の項目の ID（Long）のまま、
        // その行の位置（ピン留めか否か）はサーバ側で引く。その組織の告知でなければ旧来の id < cursor で続ける。
        AnnouncementFeedQueryRepository.OrgFeedCursor position =
                cursor == null ? null : feedQueryRepository.findOrgFeedPosition(orgId, cursor);
        Long legacyCursor = cursor != null && position == null ? cursor : null;
        int fetchSize = Math.max(effectiveLimit + 1, SCAN_BATCH);
        AnnouncementFeedQueryRepository.OrgFeedCursor lastScanned = null;
        boolean sourceHasNext = true;
        int scanned = 0;
        while (gatedRows.size() < effectiveLimit + 1 && scanned < MAX_SCANNED_ROWS) {
            List<AnnouncementFeedEntity> rows = feedQueryRepository.findOrgScopePage(
                    orgId, allowedVisibilities, position, legacyCursor, fetchSize);
            if (rows == null || rows.isEmpty()) {
                sourceHasNext = false;
                break;
            }
            scanned += rows.size();
            sourceHasNext = rows.size() == fetchSize;
            lastScanned = AnnouncementFeedQueryRepository.OrgFeedCursor.of(rows.get(rows.size() - 1));
            position = lastScanned;
            legacyCursor = null;
            List<AnnouncementFeedEntity> uniqueRows = rows.stream()
                    .filter(row -> row.getId() != null && seenIds.add(row.getId())).toList();
            List<AnnouncementFeedEntity> readable = readability.filterReadable(uniqueRows, requestUserId);
            if (!readable.isEmpty()) {
                Map<Long, GateCheckResponse> fetched = paymentGateService.checkAccessBatch(
                        ContentGateType.ANNOUNCEMENT,
                        readable.stream().map(AnnouncementFeedEntity::getId).toList(), requestUserId,
                        readable.stream().filter(row -> targetOf(row) != null)
                                .collect(Collectors.toMap(AnnouncementFeedEntity::getId,
                                        OrgAnnouncementFeedQueryService::targetOf)));
                if (fetched != null) {
                    gates.putAll(fetched);
                }
                readable.stream().filter(feed -> {
                    GateCheckResponse gate = gates.get(feed.getId());
                    // HIDDEN は管理者でも一覧から除外する。adminBypass は LOCKED 判定にだけ効く。
                    return gate != null && !gate.isTitleHidden();
                }).forEach(gatedRows::add);
            }
            if (!sourceHasNext) {
                break;
            }
        }
        boolean moreRowsAfterPage = gatedRows.size() > effectiveLimit;
        // 走査の上限で打ち切ったが、その先にまだ行がある（読める行が続きにあるかもしれない）
        boolean scanLimited = !moreRowsAfterPage && sourceHasNext && lastScanned != null;
        boolean hasNext = moreRowsAfterPage || scanLimited;
        List<AnnouncementFeedEntity> page = gatedRows.stream().limit(effectiveLimit).toList();
        List<Long> feedIds = page.stream().map(AnnouncementFeedEntity::getId).toList();
        Set<Long> readFeedIds = readService.fetchReadFeedIds(requestUserId, feedIds);
        long unreadCount = feedIds.size() - readFeedIds.size();

        List<AnnouncementFeedService.AnnouncementFeedItem> items = page.stream()
                .map(feed -> new AnnouncementFeedService.AnnouncementFeedItem(feed, readFeedIds.contains(feed.getId()),
                        adminBypass || !isLocked(gates.get(feed.getId()))
                                ? ContentAccessState.FULL.name() : ContentAccessState.LOCKED.name()))
                .toList();
        // 次ページの位置: 通常は返した最後の項目。項目が 1 件も無いまま走査上限に達したときだけ、最後に走査した行の位置から
        // 再開する（空ページで止めない）。この位置の ID は、閲覧者が読めない告知の実在 ID でありうる（報告事項）。
        Long nextCursor = null;
        if (hasNext) {
            nextCursor = !page.isEmpty() ? page.get(page.size() - 1).getId() : lastScanned.feedId();
        }
        return new AnnouncementFeedService.AnnouncementFeedResult(items, nextCursor, hasNext, unreadCount);
    }

    private static ContentGateTarget targetOf(AnnouncementFeedEntity feed) {
        if (feed == null || feed.getId() == null || feed.getScopeId() == null) {
            return null;
        }
        return new ContentGateTarget(feed.getId(), null, feed.getScopeId());
    }

    private static boolean isLocked(GateCheckResponse gate) {
        return gate != null && !gate.isAccessible() && !gate.isTitleHidden();
    }
}
