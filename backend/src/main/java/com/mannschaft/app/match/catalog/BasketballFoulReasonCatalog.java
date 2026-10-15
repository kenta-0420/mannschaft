package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * バスケットボール固有のファウル理由コードカタログ（event_type ↔ 許容 {@link BasketballFoulCode} の対応）。
 *
 * <p>コアの汎用カラム {@code match_events.card_reason_code}（VARCHAR(8)）に保持される
 * バスケ専用の理由コードについて、「{@code event_type} ごとに許容されるコード集合」を定義する。
 * 検証規約（競技非依存のコア）は
 * 「<b>その競技カタログの列挙値であること、かつ event_type と整合すること</b>」（03 §C.4b）。</p>
 *
 * <table border="1">
 *   <caption>event_type ↔ 許容コード（sports/03_basketball.md §5）</caption>
 *   <tr><th>event_type</th><th>許容コード群</th></tr>
 *   <tr><td>PERSONAL_FOUL（パーソナルファウル）</td><td>PF / SF / OF / UF</td></tr>
 *   <tr><td>TECHNICAL_FOUL（テクニカルファウル）</td><td>TF</td></tr>
 *   <tr><td>FOUL_OUT（退場）</td><td>DF（ディスクォリファイ時）/ NULL（5 ファウル累積退場時は不要）</td></tr>
 *   <tr><td>上記以外</td><td>NULL のみ（理由コード非対象）</td></tr>
 * </table>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/03_basketball.md §5</p>
 */
public final class BasketballFoulReasonCatalog {

    private BasketballFoulReasonCatalog() {
    }

    /**
     * event_type ごとの許容コード文字列集合（コードの記号値）。
     * 挿入順を保持するため LinkedHashSet を使い、UI 提示順とも整合させる。
     */
    public static final Map<MatchEventType, Set<String>> ALLOWED_CODES = Map.ofEntries(
            Map.entry(MatchEventType.PERSONAL_FOUL, personalFoulCodes()),
            Map.entry(MatchEventType.TECHNICAL_FOUL, Set.of(BasketballFoulCode.TF.name())),
            Map.entry(MatchEventType.FOUL_OUT, Set.of(BasketballFoulCode.DF.name())));

    private static Set<String> personalFoulCodes() {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(BasketballFoulCode.PF.name());
        codes.add(BasketballFoulCode.SF.name());
        codes.add(BasketballFoulCode.OF.name());
        codes.add(BasketballFoulCode.UF.name());
        return java.util.Collections.unmodifiableSet(codes);
    }

    /**
     * 当該 event_type で利用可能な理由コード集合を返す（理由コード非対象の event_type は空集合）。
     *
     * @param eventType イベント種別
     * @return 許容コード集合（非対象 event_type は空集合）
     */
    public static Set<String> allowedCodes(MatchEventType eventType) {
        return ALLOWED_CODES.getOrDefault(eventType, Set.of());
    }

    /**
     * {@code (eventType, code)} の組がカタログ上整合するか判定する。
     *
     * <p>{@code code} が NULL の場合は常に true（理由コードは任意・後から補完可能）。
     * 理由コード非対象の event_type に NULL でないコードが付与されている場合は false。</p>
     *
     * @param eventType イベント種別
     * @param code      理由コード（NULL 可）
     * @return 整合すれば true
     */
    public static boolean isValid(MatchEventType eventType, String code) {
        if (code == null) {
            // 理由コードは任意（NULL 可・後から補完できる・sports/03 §5 保守方針）
            return true;
        }
        return allowedCodes(eventType).contains(code);
    }
}
