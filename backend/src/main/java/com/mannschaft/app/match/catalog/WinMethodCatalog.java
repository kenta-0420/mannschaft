package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.Sport;

/**
 * 勝ち方（{@code matches.win_method}）検証の<b>競技別ディスパッチ機構（コア）</b>
 * （01 §D.7・案 A＝enum＋コード定数カタログ）。
 *
 * <p>ターン制（将棋/囲碁）はスコア（連続量）がなく、勝敗＝勝者 side ＋勝ち方で確定する。勝ち方は
 * 競技ごとに体系が異なる（将棋＝投了/詰み/千日手/持将棋 等、囲碁＝投了/目数差勝ち 等）。本クラスは
 * {@code match.sport} に応じて当該競技の勝ち方 enum へ委譲し、「<b>その競技カタログの列挙値であること</b>」を
 * 検証する（列挙外は 400・症状を隠さない・01 §D.7 / 03 §C.4b）。{@link ReasonCodeCatalog} と同じ
 * 競技別ディスパッチ思想（理由コードの勝ち方版）。</p>
 *
 * <table border="1">
 *   <caption>競技 → 勝ち方カタログ（01 §D.7）</caption>
 *   <tr><th>Sport</th><th>勝ち方 enum</th></tr>
 *   <tr><td>SHOGI</td><td>{@link ShogiWinMethod}（投了/詰み/時間切れ/反則勝ち/千日手/持将棋/不戦勝）</td></tr>
 *   <tr><td>GO</td><td>{@link GoWinMethod}（投了〔中押し〕/目数差勝ち/時間切れ/反則勝ち/不戦勝）</td></tr>
 *   <tr><td>球技（SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL）</td><td>{@code win_method} 非対象（NULL のみ）</td></tr>
 * </table>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.7
 *   / sports/05_shogi.md §4.1 / sports/06_go.md §4.1</p>
 */
public final class WinMethodCatalog {

    private WinMethodCatalog() {
    }

    /**
     * 当該競技で {@code winMethod}（文字列）が利用可能な勝ち方か判定する（01 §D.7）。
     *
     * <p>{@code winMethod} が NULL の場合は常に true（勝ち方は引き分け＝NULL・任意・後から補完可能）。
     * ターン制競技（SHOGI/GO）は当該競技の勝ち方 enum へ委譲し、列挙外の値・競技間の流用
     * （将棋の千日手を囲碁へ等）を弾く（症状を隠さず根治）。勝ち方を持たない球技に NULL 以外の
     * {@code win_method} が付与された場合は false（{@code win_method} 非対象）。未知の競技も false。</p>
     *
     * @param sport      競技（{@code match.sport}）
     * @param winMethod  勝ち方の enum 名（NULL 可）
     * @return 整合すれば true
     */
    public static boolean isValid(Sport sport, String winMethod) {
        if (winMethod == null) {
            // 勝ち方は任意（引き分け＝NULL・後から補完できる・§D.7）
            return true;
        }
        if (sport == null) {
            // 競技不明（縮退）は勝ち方を確定検証できない → NULL 以外は弾く
            return false;
        }
        switch (sport) {
            case SHOGI:
                return isEnumValue(ShogiWinMethod.class, winMethod);
            case GO:
                return isEnumValue(GoWinMethod.class, winMethod);
            case SOCCER:
            case FUTSAL:
            case BASKETBALL:
            case VOLLEYBALL:
                // 球技は win_method 非対象（スコアで勝敗が決まる）。NULL 以外は弾く。
                return false;
            default:
                // 未知の競技は勝ち方を確定検証できない（フォールバック明示・switch 漏れ防止）
                return false;
        }
    }

    /** {@code value} が enum {@code type} の宣言名と一致するか（valueOf の例外を握りつぶさず判定に変換）。 */
    private static <E extends Enum<E>> boolean isEnumValue(Class<E> type, String value) {
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
