package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.Sport;

import java.util.Map;
import java.util.Set;

/**
 * 多競技イベントカタログの<b>機構（コア）</b>（01 §D.3・案 A＝enum＋コード定数カタログ）。
 *
 * <p>「競技 → 利用可能 {@link MatchEventType} 集合」を {@code Map<Sport, Set<MatchEventType>>} で表現する。
 * <b>機構（このクラス）はコアに置き、各競技の具体集合（中身）は競技別カタログに置く</b>
 * （重複・ドリフト防止のため、コアは集合の具体値を定義として持たない）。</p>
 *
 * <ul>
 *   <li>SOCCER の具体集合は {@link SoccerCatalog#EVENT_TYPES}（正準 = sports/01_soccer.md §2）を参照する。</li>
 *   <li>将来競技は {@code Sport} enum 追加 → 専用カタログクラスに集合を定義 → 本 CATALOG に登録する
 *       （sports/01_soccer.md §10 新競技の追加手順）。</li>
 * </ul>
 *
 * <p>イベント記録時に {@code event_type ∈ get(match.sport)} を Service で検証する（不正値は 400・症状を隠さず根治）。
 * 本クラスはその検証規約（競技非依存のコア）を支える参照点である。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.3
 *   / sports/01_soccer.md §2</p>
 */
public final class SportEventCatalog {

    private SportEventCatalog() {
    }

    /** 競技 → 利用可能 event_type 集合（中身は各競技カタログを参照）。 */
    public static final Map<Sport, Set<MatchEventType>> CATALOG = Map.of(
            Sport.SOCCER, SoccerCatalog.EVENT_TYPES
            // 将来: Sport.BASKETBALL, Sport.FUTSAL ...（各競技カタログクラスを参照）
    );

    /**
     * 当該競技で {@code eventType} が利用可能か判定する。
     * 未知の競技（カタログ未登録）は false。
     *
     * @param sport     競技
     * @param eventType 判定対象のイベント種別
     * @return カタログに含まれていれば true
     */
    public static boolean isAllowed(Sport sport, MatchEventType eventType) {
        if (sport == null) {
            return false;
        }
        Set<MatchEventType> allowed = CATALOG.get(sport);
        return allowed != null && allowed.contains(eventType);
    }

    /**
     * 当該競技で利用可能な event_type 集合を返す（不変・未登録競技は空集合）。
     *
     * @param sport 競技
     * @return 利用可能集合（未登録競技は空集合）
     */
    public static Set<MatchEventType> allowedEventTypes(Sport sport) {
        if (sport == null) {
            return Set.of();
        }
        return CATALOG.getOrDefault(sport, Set.of());
    }
}
