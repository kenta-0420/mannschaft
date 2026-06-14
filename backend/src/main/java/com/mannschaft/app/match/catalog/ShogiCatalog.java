package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 将棋競技カタログ（{@code Sport.SHOGI} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * {@code Sport.SHOGI} 実体。本機能で初めて<b>ターン制（TURN_BASED・01 §D.6）</b>と
 * <b>団体戦（parent_match_id/board_number・01 §B.6）</b>を用いる。</p>
 *
 * <p>球技（連続時間制/セット制）との根本的差分（sports/05_shogi.md §1）:</p>
 * <ul>
 *   <li>時間（minute）・ピリオドの概念がない（{@code period} は NULL・01 §D.6）。
 *       進行量は総手数 {@code matches.total_moves}（{@link MatchEventType#MOVE_COUNT}）で表す。</li>
 *   <li>スコアという連続量がない。勝敗は {@code home/away_score} に 1-0/0-1/0-0、勝ち方は
 *       {@code win_method}（{@link ShogiWinMethod}・§4.1）で表す（§B.1.2／責務分離）。</li>
 *   <li>出場交代の概念がないため STARTER/SUB_IN 等の出場時間系 event_type を含めない
 *       （カタログ検証で弾く・出場時間算出はターン制で起動しない・01 §D.6）。</li>
 *   <li>「カード」体系がないため YELLOW_CARD/RED_CARD 等を含めず、{@code card_reason_code} は
 *       NULL のみ許容（反則は {@link ShogiWinMethod#FOUL_WIN}＋note で表現・§5）。</li>
 * </ul>
 *
 * <p>勝ち方の許容値は {@link ShogiWinMethod}、団体戦の勝ち星集計は
 * {@code MatchTeamMatchService} に集約する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/05_shogi.md §2 / §4 / §5 / §7</p>
 */
public final class ShogiCatalog {

    private ShogiCatalog() {
    }

    /**
     * 将棋で利用可能な event_type 集合（正準 = sports/05_shogi.md §2・結果系少数）。
     * 不変集合として公開する。
     */
    public static final Set<MatchEventType> EVENT_TYPES = Collections.unmodifiableSet(
            EnumSet.of(
                    // 対局結果系（ターン制共通・球技は使わない）
                    MatchEventType.GAME_RESULT,
                    MatchEventType.MOVE_COUNT,
                    MatchEventType.POSITION_PHOTO,
                    MatchEventType.COMMENT,
                    // 共通
                    MatchEventType.OTHER));
}
