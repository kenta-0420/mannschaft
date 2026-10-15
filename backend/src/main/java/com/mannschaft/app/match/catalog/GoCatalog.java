package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 囲碁競技カタログ（{@code Sport.GO} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A）の {@code Sport.GO} 実体。
 * 将棋（{@link ShogiCatalog}）と<b>同じターン制（TURN_BASED・01 §D.6）</b>であり、
 * event_type 集合は将棋と同一（sports/06_go.md §2）。差分は勝ち方の語彙
 * （{@link GoWinMethod}＝投了〔中押し〕/目数差勝ち 等）と一部統計・UX ラベルのみ。</p>
 *
 * <ul>
 *   <li>時間・ピリオド概念なし（{@code period} NULL）・進行量は総手数 {@code total_moves}。</li>
 *   <li>勝敗は {@code home/away_score} に 1-0/0-1/0-0（持碁＝引分は 0-0）、勝ち方は
 *       {@code win_method}（{@link GoWinMethod}・§4.1）。目数差勝ち（POINTS_WIN）の目数差は
 *       任意で {@code detail} に保持する（§2.1）。</li>
 *   <li>出場交代・カード体系なし（{@code card_reason_code} は NULL のみ・反則は FOUL_WIN＋note）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/06_go.md §2 / §4 / §5 / §7</p>
 */
public final class GoCatalog {

    private GoCatalog() {
    }

    /**
     * 囲碁で利用可能な event_type 集合（正準 = sports/06_go.md §2・将棋と同一）。
     * 不変集合として公開する。
     */
    public static final Set<MatchEventType> EVENT_TYPES = Collections.unmodifiableSet(
            EnumSet.of(
                    // 対局結果系（ターン制共通・将棋と同一）
                    MatchEventType.GAME_RESULT,
                    MatchEventType.MOVE_COUNT,
                    MatchEventType.POSITION_PHOTO,
                    MatchEventType.COMMENT,
                    // 共通
                    MatchEventType.OTHER));
}
