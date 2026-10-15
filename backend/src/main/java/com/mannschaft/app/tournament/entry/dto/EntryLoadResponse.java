package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * チームメンバーからの一括ロード結果レスポンスDTO。
 *
 * <p>F08.7 Phase 9: loadFromTeam API のレスポンス。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryLoadResponse {

    /** 新規追加された件数 */
    int added;

    /** スキップされた件数（既存エントリー済み or 非アクティブメンバー） */
    int skipped;

    /** ロード後の合計エントリー数 */
    int total;

    /** ロード後のエントリー表メンバー一覧（sort_order順） */
    List<EntryMemberResponse> entryMembers;
}
