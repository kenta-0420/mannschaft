package com.mannschaft.app.tournament.leaguetransfer;

/**
 * リーグ移籍の方向（F08.7.1 / 03 §3.1）。
 *
 * <p>昇格・降格はどちらも「プッシュ＋承認」の対称モデルで進む（§1.1）。方向が違うだけで
 * 状態機械（{@link LeagueTransferStatus}）・API 形は完全に共通。</p>
 */
public enum LeagueTransferDirection {
    /** 昇格: 下位 org（手放す側）が送り出し → 上位 org（受け入れる側）が承認。 */
    PROMOTION,
    /** 降格: 上位 org（手放す側）が送り出し → 下位 org（受け入れる側）が承認。 */
    RELEGATION
}
