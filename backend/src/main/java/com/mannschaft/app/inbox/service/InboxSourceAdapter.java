package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxItemDto;

import java.util.List;

/**
 * F04.11 統合通知インボックス：ソースアダプタ・インターフェース。
 *
 * <p>各通知ソース（5 種）を統一 DTO {@code InboxItemDto} に変換する。新ソース追加は
 * 「アダプタ 1 実装の追加」で済む（保守性）。読み取りのみ・書き込み越境なし（CLAUDE.md 原則5）。
 * 設計書: 03_business_logic.md §2。</p>
 *
 * <p>MVP ではアダプタ実装（NOTIFICATION / TODO_DUE 先行、続けて残 3 ソース）を後続フェーズで追加する。</p>
 */
public interface InboxSourceAdapter {

    /**
     * このアダプタが担当するソース種別を返す。
     */
    InboxSourceType sourceType();

    /**
     * 当該ユーザーの通知をソースから読み取り、統一 DTO へ正規化して返す（ソース毎ハードリミット付き）。
     *
     * @param userId 対象ユーザーID
     * @return 正規化済みインボックス項目（triage 状態/ラベルは未マージ＝集約サービスで被せる）
     */
    List<InboxItemDto> fetch(Long userId);
}
