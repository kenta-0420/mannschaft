package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;

/**
 * 理由コード検証の<b>競技別ディスパッチ機構（コア）</b>（01 §D.5・03 §C.4b・案 A）。
 *
 * <p>コアの汎用カラム {@code match_events.card_reason_code}（VARCHAR(8)）に保持される理由コードは、
 * <b>競技ごとに体系が異なる</b>（サッカー/フットサル＝C/S コード、バスケ＝FIBA ファウルコード）。
 * 本クラスは {@code match.sport} に応じて当該競技の理由コードカタログへ委譲し、
 * 「<b>その競技カタログの列挙値であること、かつ event_type と整合すること</b>」を検証する（03 §C.4b）。</p>
 *
 * <p>設計が示す {@code Map<Sport, CardReasonCatalog>} 登録（01 §D.5）の具体実装。
 * 各競技カタログ（{@link CardReasonCatalog} / {@link BasketballFoulReasonCatalog}）は
 * 静的ユーティリティのため、ここでは {@code switch} による明示ディスパッチで結線する
 * （重複・ドリフト防止のため、コアは各競技の許容コード集合の中身を持たない）。</p>
 *
 * <table border="1">
 *   <caption>競技 → 理由コードカタログ（01 §D.5）</caption>
 *   <tr><th>Sport</th><th>理由コードカタログ</th></tr>
 *   <tr><td>SOCCER / FUTSAL</td><td>{@link CardReasonCatalog}（C/S コード）</td></tr>
 *   <tr><td>BASKETBALL</td><td>{@link BasketballFoulReasonCatalog}（FIBA ファウルコード）</td></tr>
 *   <tr><td>VOLLEYBALL / SHOGI / GO</td><td>理由コード非対象（後続波・現状は NULL 以外を弾く）</td></tr>
 * </table>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.5
 *   / sports/03_basketball.md §5</p>
 */
public final class ReasonCodeCatalog {

    private ReasonCodeCatalog() {
    }

    /**
     * 当該競技・event_type において理由コードが整合するか判定する（03 §C.4b）。
     *
     * <p>{@code code} が NULL の場合は常に true（理由コードは任意・後から補完可能）。
     * 競技ごとに対応する理由コードカタログへ委譲し、サッカーの C/S コードをバスケへ、
     * バスケのファウルコードをサッカーへ、といった<b>競技間の流用</b>を弾く（症状を隠さず根治・03 §5）。</p>
     *
     * <p>理由コードを定義しない競技（VOLLEYBALL/SHOGI/GO・後続波）に NULL 以外のコードが
     * 付与された場合は false（理由コード非対象）。未知の競技も false（フォールバック明示）。</p>
     *
     * @param sport     競技（{@code match.sport}）
     * @param eventType イベント種別
     * @param code      理由コード（NULL 可）
     * @return 整合すれば true
     */
    public static boolean isValid(Sport sport, MatchEventType eventType, String code) {
        if (code == null) {
            // 理由コードは任意（NULL 可・後から補完できる・03 §C.4b）
            return true;
        }
        if (sport == null) {
            // 競技不明（縮退）は理由コードを確定検証できない → NULL 以外は弾く
            return false;
        }
        switch (sport) {
            case SOCCER:
            case FUTSAL:
                // サッカー/フットサルは同一イベント集合・同一規律コード体系（C/S コード・03 §1）
                return CardReasonCatalog.isValid(eventType, code);
            case BASKETBALL:
                return BasketballFoulReasonCatalog.isValid(eventType, code);
            case VOLLEYBALL:
            case SHOGI:
            case GO:
                // 後続波で理由コード体系を定義するまでは理由コード非対象（NULL 以外を弾く）
                return false;
            default:
                // 未知の競技は理由コードを確定検証できない（フォールバック明示・switch 漏れ防止）
                return false;
        }
    }
}
