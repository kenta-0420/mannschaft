package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 警告・退場の理由コードカタログ（<b>サッカー固有</b>・event_type ↔ 許容コードの対応）。
 *
 * <p>コアの汎用カラム {@code match_events.card_reason_code}（VARCHAR(8)）に保持される理由コードについて、
 * 「{@code event_type} ごとに許容されるコード集合」を定義する。検証規約（競技非依存のコア）は
 * 「<b>その競技カタログの列挙値であること、かつ event_type と整合すること</b>」（03 §C.4b）。
 * 本クラスはサッカーの具体対応（sports/01_soccer.md §5.3）を実装する。</p>
 *
 * <table border="1">
 *   <caption>event_type ↔ 許容コード（sports/01_soccer.md §5.3）</caption>
 *   <tr><th>event_type</th><th>許容コード群</th></tr>
 *   <tr><td>YELLOW_CARD（警告）</td><td>CautionCode C1〜C8</td></tr>
 *   <tr><td>RED_CARD（一発退場）</td><td>SendingOffCode S1〜S6（CS 除く）</td></tr>
 *   <tr><td>SECOND_YELLOW（2 枚目の警告）</td><td>CS のみ（＝警告 2 回）／併せて警告コード自体は §8.3 で UI 提示するが、card_reason_code は CS を正とする</td></tr>
 *   <tr><td>上記以外</td><td>NULL のみ（理由コード非対象）</td></tr>
 * </table>
 *
 * <p>多競技拡張時は競技ごとに別カタログ（理由コード集合）を持つ（バスケのテクニカルファウル等は別体系）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/01_soccer.md §5
 *   / 01_domain_and_ddl.md §D.5</p>
 */
public final class CardReasonCatalog {

    private CardReasonCatalog() {
    }

    /**
     * event_type ごとの許容コード文字列集合（コードの記号値）。
     * 挿入順を保持するため LinkedHashSet を使い、UI 提示順とも整合させる。
     */
    public static final Map<MatchEventType, Set<String>> ALLOWED_CODES = Map.of(
            MatchEventType.YELLOW_CARD, cautionCodes(),
            MatchEventType.RED_CARD, sendingOffCodesExceptSecondYellow(),
            MatchEventType.SECOND_YELLOW, Set.of(SendingOffCode.CS.name()));

    private static Set<String> cautionCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (CautionCode c : CautionCode.values()) {
            codes.add(c.name());
        }
        return java.util.Collections.unmodifiableSet(codes);
    }

    private static Set<String> sendingOffCodesExceptSecondYellow() {
        Set<String> codes = new LinkedHashSet<>();
        for (SendingOffCode s : SendingOffCode.values()) {
            if (s != SendingOffCode.CS) {
                codes.add(s.name());
            }
        }
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
            // 理由コードは任意（NULL 可・後から補完できる・§5.3）
            return true;
        }
        return allowedCodes(eventType).contains(code);
    }
}
