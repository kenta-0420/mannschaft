package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F04.11 統合通知インボックス：通知可視性チェッカー（IDOR 防止の協力オブジェクト）。
 *
 * <p>triage（snooze/archive/...）の対象 {@code (sourceType, sourceId)} が本人に可視かを、
 * 一覧取得と同じアダプタの {@link InboxSourceAdapter#isVisibleTo} で判定する。書き込み前検証に使用し、
 * 可視でない対象へのオーバーレイ行作成（攻撃によるテーブル肥大化）を防ぐ。
 * 設計書: 04_security_operations.md §1.2。</p>
 *
 * <p>集約サービスとは責務を分離し、triage サービスはこのチェッカー 1 つに依存する（テスト容易性）。</p>
 */
@Component
public class InboxItemVisibilityChecker {

    private final Map<InboxSourceType, InboxSourceAdapter> adaptersByType;

    public InboxItemVisibilityChecker(List<InboxSourceAdapter> adapters) {
        this.adaptersByType = new EnumMap<>(InboxSourceType.class);
        for (InboxSourceAdapter adapter : adapters) {
            this.adaptersByType.put(adapter.sourceType(), adapter);
        }
    }

    /**
     * 指定通知が当該ユーザーに可視かを判定する。
     *
     * <p>担当アダプタが存在しない sourceType（MVP 未実装ソース等）は可視でないとみなす（false）。</p>
     *
     * @param userId     対象ユーザーID
     * @param sourceType 通知ソース種別
     * @param sourceId   各ソース PK
     * @return 本人に可視なら true
     */
    public boolean isVisibleTo(Long userId, InboxSourceType sourceType, Long sourceId) {
        InboxSourceAdapter adapter = adaptersByType.get(sourceType);
        if (adapter == null) {
            return false;
        }
        return adapter.isVisibleTo(userId, sourceId);
    }
}
