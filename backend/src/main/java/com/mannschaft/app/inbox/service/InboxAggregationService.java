package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：集約サービス。
 *
 * <p>5 ソースを読み取り集約し {@code InboxItem} に正規化、triage 状態/ラベルのオーバーレイを
 * まとめ取りしてマージする（手本: {@code DashboardService.getPersonalDashboard}）。
 * 設計書: 03_business_logic.md §1・§4。</p>
 *
 * <p><b>骨組み（一陣 → 二陣でオーバーレイ Repository を注入）</b>: ロジック本体は三陣で実装する。
 * 現段階ではコンパイルが通る空骨格。</p>
 *
 * <p><b>二陣メモ（test-first・三陣へ申し送り）</b>: 設計書 03_business_logic.md §4 の
 * 「オーバーレイ/ラベルは user_id でまとめ取り（item 毎に引かない＝N+1 回避）」を
 * 検証可能にするため、{@link InboxItemStateRepository} と {@link InboxLabelLinkRepository} を
 * 注入フィールドに追加した。三陣は集約フローでこれらを <b>ソース件数に依らず定数回</b>
 * （状態はソース種別集合で 1 回、ラベルは sourceType ごとに 1 回など）呼ぶこと。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxAggregationService {

    private final List<InboxSourceAdapter> sourceAdapters;
    private final InboxPriorityNormalizer priorityNormalizer;
    private final InboxItemStateRepository itemStateRepository;
    private final InboxLabelLinkRepository labelLinkRepository;

    /**
     * インボックス一覧を集約取得する（フィルタ・ページング）。
     *
     * @param userId      対象ユーザーID
     * @param stateFilter 状態フィルタ（INBOX/SNOOZED/ARCHIVED/ALL）
     * @param priorities  緊急度フィルタ（複数可・null/空で全件）
     * @param sourceTypes 種類フィルタ（複数可・null/空で全件）
     * @param labelId     ラベル絞り込み（null で全件）
     * @param page        ページ番号（0 始まり）
     * @param size        ページサイズ（1〜50）
     * @return 一覧レスポンス
     */
    public InboxPageResponse getInbox(
            Long userId,
            String stateFilter,
            List<InboxPriority> priorities,
            List<InboxSourceType> sourceTypes,
            UUID labelId,
            int page,
            int size) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * 状態別・緊急度別・種類別の件数サマリを取得する（タブ/バッジ用）。
     *
     * @param userId 対象ユーザーID
     * @return サマリレスポンス
     */
    public InboxSummaryResponse getSummary(Long userId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
