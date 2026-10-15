package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.TeamSide;

/**
 * バレーボールのセット勝敗・デュース・試合決着の<b>純ロジック</b>（sports/04_volleyball.md §4）。
 *
 * <p>Spring 非依存のユーティリティ。セット内スコア（ラリーポイント・25 点/最終 15 点・デュース＝2 点差）から
 * セット勝者（{@code winner_side}）を導出し、獲得セット数（matches.home_score/away_score）から
 * best-of-5 の試合決着（3 セット先取・引分けなし）を判定する。</p>
 *
 * <p><b>正本の責務分離（01 §B.5 / §B.1.2）</b>: セット内スコアの正本は {@code match_sets}、
 * 獲得セット数（試合の本戦スコア）の正本は {@code matches.home_score/away_score}。本クラスは
 * その導出規則のみを持ち、永続化は {@code MatchSetService} が担う。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §4.1 / §4.2 / §4.3
 *   / 01_domain_and_ddl.md §B.1.2 / §D.6</p>
 */
public final class VolleyballSetRules {

    /** 通常セットの目標点（§4.2）。 */
    private static final int NORMAL_SET_TARGET = 25;
    /** 最終セット（第 5）の目標点（§4.2）。 */
    private static final int FINAL_SET_TARGET = 15;
    /** デュース（2 点差必須・§4.2）。 */
    private static final int MIN_LEAD = 2;

    /** best-of-5（既定・5 セットマッチ＝3 セット先取）。 */
    private static final String BEST_OF_5 = "BEST_OF_5";
    /** best-of-3（3 セットマッチ＝2 セット先取）。 */
    private static final String BEST_OF_3 = "BEST_OF_3";

    private VolleyballSetRules() {
    }

    /**
     * 当該セットの目標点を返す（§4.2）。
     *
     * @param isFinalSet 最終セット（第 5＝15 点制）か
     * @return 目標点（通常 25 / 最終 15）
     */
    public static int setTarget(boolean isFinalSet) {
        return isFinalSet ? FINAL_SET_TARGET : NORMAL_SET_TARGET;
    }

    /**
     * セットが決着しているか（目標点到達かつ 2 点差・デュース・§4.2）。
     *
     * <p>{@code max(home, away) >= setTarget && abs(home - away) >= 2}。
     * 24-24・25-24 は未決着、26-24・27-25 は決着。</p>
     *
     * @param homePoints ホーム得点
     * @param awayPoints アウェイ得点
     * @param isFinalSet 最終セット（15 点制）か
     * @return 決着していれば true
     */
    public static boolean isSetDecided(int homePoints, int awayPoints, boolean isFinalSet) {
        int target = setTarget(isFinalSet);
        int max = Math.max(homePoints, awayPoints);
        int lead = Math.abs(homePoints - awayPoints);
        return max >= target && lead >= MIN_LEAD;
    }

    /**
     * セット勝者を導出する（決着していなければ null・§4.2）。
     *
     * @param homePoints ホーム得点
     * @param awayPoints アウェイ得点
     * @param isFinalSet 最終セット（15 点制）か
     * @return 勝者サイド（未決着は null）
     */
    public static TeamSide resolveSetWinner(int homePoints, int awayPoints, boolean isFinalSet) {
        if (!isSetDecided(homePoints, awayPoints, isFinalSet)) {
            return null;
        }
        return homePoints > awayPoints ? TeamSide.HOME : TeamSide.AWAY;
    }

    /**
     * 試合決着に必要な勝ちセット数を返す（§4.1）。
     *
     * @param periodFormat 'BEST_OF_5'（3 セット先取）/ 'BEST_OF_3'（2 セット先取）/ null（既定 best-of-5）
     * @return 必要勝ちセット数（best-of-5=3 / best-of-3=2）
     */
    public static int setsToWin(String periodFormat) {
        if (BEST_OF_3.equalsIgnoreCase(periodFormat)) {
            return 2;
        }
        // BEST_OF_5 および未指定は 3 セット先取（既定）
        return 3;
    }

    /**
     * 当該セット番号が最終セット（デュース 15 点制）か（§4.1）。
     *
     * <p>best-of-5 では第 5 セット、best-of-3 では第 3 セットが最終。それ以外は通常 25 点制。</p>
     *
     * @param setNumber    セット番号（1〜）
     * @param periodFormat 'BEST_OF_5' / 'BEST_OF_3' / null（既定 best-of-5）
     * @return 最終セットなら true
     */
    public static boolean isFinalSet(int setNumber, String periodFormat) {
        int maxSets = BEST_OF_3.equalsIgnoreCase(periodFormat) ? 3 : 5;
        return setNumber == maxSets;
    }

    /**
     * 獲得セット数から試合が決着しているか（どちらかが必要勝ちセット数に到達・§4.1）。
     *
     * @param homeSetsWon ホームの獲得セット数
     * @param awaySetsWon アウェイの獲得セット数
     * @param periodFormat 試合形式
     * @return 試合決着なら true
     */
    public static boolean isMatchDecided(int homeSetsWon, int awaySetsWon, String periodFormat) {
        int needed = setsToWin(periodFormat);
        return homeSetsWon >= needed || awaySetsWon >= needed;
    }

    /**
     * COMPLETED 遷移の可否（獲得セット数が確定し、3 セット先取で決着し、引分けでないこと・§4.3 / §B.1.2）。
     *
     * <p>バレーに引き分け（D）はない。獲得セット数（matches.home_score/away_score）が両方確定し、
     * 勝者が必要勝ちセット数に到達し、かつ両者が同数でないことを要求する。</p>
     *
     * @param homeSetsWon  ホーム獲得セット数（matches.home_score・NULL 可）
     * @param awaySetsWon  アウェイ獲得セット数（matches.away_score・NULL 可）
     * @param periodFormat 試合形式
     * @return COMPLETED 可能なら true
     */
    public static boolean isMatchCompletable(Integer homeSetsWon, Integer awaySetsWon, String periodFormat) {
        if (homeSetsWon == null || awaySetsWon == null) {
            return false;
        }
        if (homeSetsWon.equals(awaySetsWon)) {
            // 引分けは存在しない（バレーに D なし・§4.3）
            return false;
        }
        return isMatchDecided(homeSetsWon, awaySetsWon, periodFormat);
    }
}
