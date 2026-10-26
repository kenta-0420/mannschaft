package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * バスケットボール競技カタログ（{@code Sport.BASKETBALL} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * {@code Sport.BASKETBALL} 実体。</p>
 *
 * <p>サッカーとの主な差分（sports/03_basketball.md §1）:</p>
 * <ul>
 *   <li>得点種別: {@link MatchEventType#FIELD_GOAL_2}（+2）/ {@link MatchEventType#FIELD_GOAL_3}（+3）/
 *       {@link MatchEventType#FREE_THROW}（+1）― GOAL は含まない</li>
 *   <li>ピリオド: 4 クォーター＋OVERTIME（FIRST_HALF/SECOND_HALF は含まない）</li>
 *   <li>反則体系: {@link MatchEventType#PERSONAL_FOUL}/{@link MatchEventType#TECHNICAL_FOUL}/
 *       {@link MatchEventType#FOUL_OUT}。理由コードは {@link BasketballFoulCode}（§5）</li>
 *   <li>PK 戦（PENALTY_SHOOTOUT）はバスケに存在しないため含まない（sports/03 §4.1）</li>
 *   <li>オウンゴール（OWN_GOAL）はバスケに存在しないため含まない（sports/03 §4.2）</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/03_basketball.md §2 / §7</p>
 */
public final class BasketballCatalog {

    private BasketballCatalog() {
    }

    /**
     * バスケットボールで利用可能な event_type 集合（正準 = sports/03_basketball.md §2）。
     * 不変集合として公開する。
     */
    public static final Set<MatchEventType> EVENT_TYPES = java.util.Collections.unmodifiableSet(
            EnumSet.of(
                    // 出場・交代（コア共通）
                    MatchEventType.STARTER,
                    MatchEventType.SUB_IN,
                    MatchEventType.SUB_OUT,
                    // 得点（バスケ固有・重み付き: 2P/3P/FT）
                    MatchEventType.FIELD_GOAL_2,
                    MatchEventType.FIELD_GOAL_3,
                    MatchEventType.FREE_THROW,
                    // シュート失敗（任意記録・スコア非影響）
                    MatchEventType.SHOT_MISS,
                    // 技術統計（バスケ固有）
                    MatchEventType.REBOUND,
                    MatchEventType.STEAL,
                    MatchEventType.BLOCK,
                    MatchEventType.TURNOVER,
                    // アシスト（コア共通・バスケでは FIELD_GOAL と linked_event_id で連鎖）
                    MatchEventType.ASSIST,
                    // 反則（バスケ固有・理由コードは BasketballFoulCode）
                    MatchEventType.PERSONAL_FOUL,
                    MatchEventType.TECHNICAL_FOUL,
                    MatchEventType.FOUL_OUT,
                    // 共通
                    MatchEventType.INJURY,
                    MatchEventType.PERIOD_START,
                    MatchEventType.PERIOD_END,
                    MatchEventType.OTHER));

    /**
     * バスケットボールのポジション語彙（大分類・正準 = sports/03_basketball.md §7）。
     *
     * <p>{@code player_appearances.position} の必須語彙（先発 5 人）。
     * doughnut「ポジション傾向」はこの 5 分類で束ねる。</p>
     */
    public static final List<String> POSITIONS = List.of("PG", "SG", "SF", "PF", "C");
}
