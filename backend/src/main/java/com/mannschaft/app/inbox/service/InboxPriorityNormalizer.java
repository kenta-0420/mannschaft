package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import org.springframework.stereotype.Component;

/**
 * F04.11 統合通知インボックス：自動緊急度の正規化（純粋関数）。
 *
 * <p>各ソースの優先度を単一 {@link InboxPriority} に写像する。毎リクエスト導出（永続化しない）。
 * 正規化表は設計書 01_data_model.md §3.2 を参照。</p>
 *
 * <p><b>骨組み（一陣）</b>: ロジック本体は三陣で実装する。現段階ではコンパイルが通る空骨格。</p>
 */
@Component
public class InboxPriorityNormalizer {

    /**
     * ソース種別と元の優先度文字列から {@link InboxPriority} を導出する。
     *
     * @param sourceType    ソース種別
     * @param rawPriority   ソース固有の優先度（null 可）
     * @return 正規化後の緊急度
     */
    public InboxPriority normalize(InboxSourceType sourceType, String rawPriority) {
        throw new UnsupportedOperationException("not implemented");
    }
}
