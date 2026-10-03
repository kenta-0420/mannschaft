package com.mannschaft.app.notification.fanout;

/**
 * 受信者ページ 1 回ぶんの取得条件（Issue #2871）。
 *
 * <p>従来 {@link FanoutRecipientSource} は 3 / 4 / 6 引数のオーバーロードが既定実装で
 * 順に委譲し合う構造だった。引数が増えるたびに「override し忘れた実装だけ古い挙動になる」
 * 事故（応援者トグルの無視・シャード述語の脱落）が起きうるため、条件を 1 つの record に
 * まとめ、{@link FanoutRecipientSource#nextPage(FanoutPageRequest)} 1 メソッドへ集約する。</p>
 *
 * @param scopeRef          多型スコープ参照（村＝UUID 文字列 / チーム・組織＝ID 文字列 等）
 * @param cursorSubjectId   直前チャンク末尾の subject_id（初回は {@code 0L}）。
 *                          <b>再開カーソルは user_id ただ 1 本</b>であり、locale はカーソルに含めない
 *                          （at-least-once・欠落なしの不変条件を維持する）
 * @param limit             1 チャンクの最大件数
 * @param includeSupporters 応援者（純 SUPPORTER）を配信対象に含めるか
 * @param shardIndex        自シャードの番号（{@code 0..shardCount-1}）
 * @param shardCount        シャード総数（{@code 1} なら分割なし＝従来経路）
 */
public record FanoutPageRequest(String scopeRef,
                                long cursorSubjectId,
                                int limit,
                                boolean includeSupporters,
                                int shardIndex,
                                int shardCount) {

    /** シャード分割していない（{@code shardCount <= 1}）か。 */
    public boolean isSingleShard() {
        return shardCount <= 1;
    }
}
