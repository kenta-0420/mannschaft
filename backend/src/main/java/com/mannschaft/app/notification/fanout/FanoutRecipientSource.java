package com.mannschaft.app.notification.fanout;

import java.util.List;

/**
 * fan-out 受信者解決の戦略インターフェース（P2・横展開の要）。
 *
 * <p>耐久ジョブ {@link NotificationFanoutJob} の {@code scope_type} をキーに、対応する実装が
 * 受信者 subject_id を<b>キーセットページング</b>で 1 チャンクずつ供給する。ワーカーはこの seam を
 * 通じてのみ受信者を得るため、新しいスコープ（TEAM / ORGANIZATION 等）を足すときは本 IF の実装を
 * 1 つ追加するだけでよく、ジョブ表もワーカーも変えなくてよい（AC-15）。</p>
 */
public interface FanoutRecipientSource {

    /**
     * 本実装が担うスコープ種別（{@link NotificationFanoutJob#getScopeType()} と一致させる）。
     * {@link FanoutRecipientSourceRegistry} の解決キーになる。
     */
    String scopeType();

    /**
     * {@code cursorSubjectId} より大きい受信者 subject_id を昇順で最大 {@code limit} 件返す。
     *
     * <p>呼び出し側は「返却末尾の subject_id を次カーソルにして、結果が {@code limit} 未満になるまで繰り返す」
     * ことで全受信者を漏れなく列挙する。全件を一度に {@code List} 化しない（50 万人規模のメモリ有界走査）。</p>
     *
     * @param scopeRef        受信者を絞り込む多型スコープ参照（ジョブの {@code scope_ref}・村=UUID 文字列 等）。
     *                        各実装が自ドメインの型（UUID / long 等）へ復元する。
     * @param cursorSubjectId 直前チャンク末尾の subject_id（初回は最小値未満＝{@code 0L} 等）
     * @param limit           1 チャンクの最大件数
     * @return {@code subject_id > cursorSubjectId} の受信者 subject_id を昇順に最大 limit 件
     */
    List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit);
}
