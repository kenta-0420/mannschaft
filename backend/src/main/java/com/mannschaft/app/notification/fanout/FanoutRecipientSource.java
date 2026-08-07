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

    /**
     * {@code includeSupporters}（応援者を配信対象に含めるか）を運搬する 4 引数版（Wave-2・ORG 耐久 fan-out）。
     *
     * <p>ワーカーは常にこの 4 引数版を呼ぶ。既定実装は {@code includeSupporters} を<b>無視</b>して 3 引数版へ委譲し、
     * トグルを持たない VILLAGE / TEAM の挙動を不変に保つ（後方互換）。ORGANIZATION など応援者トグルを持つ実装のみ
     * 本メソッドを override して {@code includeSupporters} を受信者解決へ渡す。</p>
     *
     * @param includeSupporters 応援者（純 SUPPORTER）を配信対象に含めるか（ジョブ {@code include_supporters}）
     */
    default List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit, boolean includeSupporters) {
        // 既定はトグル無視（VILLAGE / TEAM は母集団が応援者トグルに依存しないため 3 引数版へ委譲）。
        return nextPage(scopeRef, cursorSubjectId, limit);
    }

    /**
     * シャード対応 6 引数版（CMP-001⑤ ワーカー並列化・出陣-N で実装。現状は red を成立させるスタブ）。
     *
     * <p>{@code shardIndex}/{@code shardCount} を運搬し、{@code subject_id % shardCount == shardIndex} の
     * 受信者だけを返す想定（各シャードが互いに素な部分集合を担当・母集団の和集合は全件に一致）。
     * 現時点では未実装であり、呼び出すと必ず {@link UnsupportedOperationException} を投げる。
     * 実装は出陣（CMP-001⑤）で行う。</p>
     */
    default List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit, boolean includeSupporters,
                                int shardIndex, int shardCount) {
        throw new UnsupportedOperationException(
                "シャード対応 nextPage は出陣（CMP-001⑤）で実装予定・現状未実装");
    }
}
