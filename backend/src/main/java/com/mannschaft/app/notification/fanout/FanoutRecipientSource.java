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
     * シャード対応 6 引数版（CMP-001⑤ ワーカー並列化）。
     *
     * <p>{@code shardIndex}/{@code shardCount} を運搬し、{@code subject_id % shardCount == shardIndex} の
     * 受信者だけを返す（各シャードが互いに素な部分集合を担当・母集団の和集合は全件に一致）。</p>
     *
     * <p>既定実装は {@code shardCount <= 1} のとき分割せず 4 引数版へ委譲する（VILLAGE / TEAM・小規模 ORG の
     * 単一シャード経路は従来と完全一致）。{@code shardCount > 1} のシャード分割に対応しないソース
     * （VILLAGE / TEAM）は enqueue が {@code shard_count=1} でしか発行しないため本経路（>1）に到達しない。
     * 万一到達したら未対応として {@link UnsupportedOperationException} を投げる（握り潰さず露見させる）。
     * シャード分割を要する ORGANIZATION のみ本メソッドを override して {@code MOD(user_id, shardCount)} 述語を渡す。</p>
     */
    default List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit, boolean includeSupporters,
                                int shardIndex, int shardCount) {
        if (shardCount <= 1) {
            return nextPage(scopeRef, cursorSubjectId, limit, includeSupporters);
        }
        throw new UnsupportedOperationException(
                scopeType() + " はシャード分割に非対応（shard_count>1 で呼ばれた）");
    }

    /**
     * 受信者総数を返す（enqueue の自動シャード数算出に使う・CMP-001⑤）。
     *
     * <p>既定は {@code -1}（＝カウント非対応／シャード対象外）を返し、enqueue はこれを見て {@code shard_count=1} とする。
     * 分割対象外の VILLAGE / TEAM は override しない（挙動不変）。母集団しきい値でシャード化する
     * ORGANIZATION のみ本メソッドを override し、母集団の {@code COUNT(DISTINCT user_id)} を返す。</p>
     *
     * @param scopeRef          多型スコープ参照（各実装が自ドメインの型へ復元する）
     * @param includeSupporters 応援者（純 SUPPORTER）を配信対象に含めるか
     * @return 受信者総数（{@code >= 0}）。カウント非対応なら {@code -1}
     */
    default long countRecipients(String scopeRef, boolean includeSupporters) {
        return -1L;
    }
}
