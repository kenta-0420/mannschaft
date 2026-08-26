package com.mannschaft.app.notification.fanout;

import java.util.List;

/**
 * fan-out 受信者解決の戦略インターフェース（P2・横展開の要）。
 *
 * <p>耐久ジョブ {@link NotificationFanoutJob} の {@code scope_type} をキーに、対応する実装が
 * 受信者を<b>キーセットページング</b>で 1 チャンクずつ供給する。ワーカーはこの seam を
 * 通じてのみ受信者を得るため、新しいスコープ（TEAM / ORGANIZATION 等）を足すときは本 IF の実装を
 * 1 つ追加するだけでよく、ジョブ表もワーカーも変えなくてよい（AC-15）。</p>
 *
 * <h2>Issue #2871: 戻り値を {@code List<FanoutRecipient>} へ・引数を 1 record へ集約</h2>
 * <p>受信者ごとに文面のロケールを変えるため、戻り値を {@code List<Long>} から
 * {@link FanoutRecipient}（user_id ＋ locale）のリストへ広げた。各実装の keyset クエリは
 * {@code users} を<b>主キーで JOIN</b> して {@code locale} も一緒に取るため、ロケール解決のための
 * DB 往復は 1 回も増えない（実測は PR 本文の EXPLAIN 参照）。</p>
 *
 * <p>あわせて、3 / 4 / 6 引数のオーバーロードが既定実装で順に委譲し合う構造をやめ、
 * {@link FanoutPageRequest} 1 つを受ける単一メソッドに集約した。旧構造は引数が増えるたびに
 * 「override し忘れた実装だけ古い挙動になる」事故（応援者トグルの無視・シャード述語の脱落）を
 * 生む余地があり、実際シャード非対応の実装は {@code UnsupportedOperationException} を
 * 投げる既定実装でそれを塞いでいた。単一メソッドなら各実装が全条件を必ず受け取る。</p>
 */
public interface FanoutRecipientSource {

    /**
     * 本実装が担うスコープ種別（{@link NotificationFanoutJob#getScopeType()} と一致させる）。
     * {@link FanoutRecipientSourceRegistry} の解決キーになる。
     */
    String scopeType();

    /**
     * {@code request.cursorSubjectId()} より大きい受信者を subject_id 昇順で最大
     * {@code request.limit()} 件返す。
     *
     * <p>呼び出し側は「返却末尾の userId を次カーソルにして、結果が limit 未満になるまで繰り返す」
     * ことで全受信者を漏れなく列挙する。全件を一度に {@code List} 化しない（50 万人規模のメモリ有界走査）。
     * <b>再開カーソルは user_id ただ 1 本</b>であり locale は含まない（at-least-once・欠落なしを維持）。</p>
     *
     * <p>{@code request.shardCount() > 1} のシャード分割に対応しない実装（VILLAGE / TEAM 等）は
     * enqueue が {@code shard_count=1} でしか発行しないため本経路に到達しない。万一到達したら
     * 未対応として {@link UnsupportedOperationException} を投げること（握り潰さず露見させる）。</p>
     */
    List<FanoutRecipient> nextPage(FanoutPageRequest request);

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
