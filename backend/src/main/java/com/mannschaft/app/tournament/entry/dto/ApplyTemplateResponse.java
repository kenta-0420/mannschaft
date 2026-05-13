package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * エントリーテンプレート適用結果レスポンスDTO。
 *
 * <p>F08.7 Phase 9-B: applyTemplate API のレスポンス。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyTemplateResponse {

    /** 新規追加された件数 */
    int applied;

    /** スキップされた件数（既存エントリー済み） */
    int skipped;

    /** 非アクティブメンバーのためスキップされた件数 */
    int skippedInactive;

    /** 適用後の合計エントリー数 */
    int total;

    /** 適用後のエントリー表メンバー一覧（sort_order順） */
    List<EntryMemberResponse> entryMembers;
}
