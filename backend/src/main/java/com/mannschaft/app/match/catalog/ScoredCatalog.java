package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 採点競技カタログ（{@code Sport.FIGURE_SKATING} / {@code Sport.GYMNASTICS} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * 採点競技（<b>第 4 状態モデル類型 SCORED・01 §D.8 / sports/07_scored.md</b>）実体。
 * フィギュアスケートと体操は<b>同一の event_type 集合</b>を共有する（合計点に還元される MVP では競技差が消える・§2.1）。</p>
 *
 * <p>球技（連続時間制/セット制）・盤上（ターン制）との根本的差分（sports/07_scored.md §1 / §3）:</p>
 * <ul>
 *   <li>時間（minute）・ピリオドの概念がない（{@code period} は NULL・01 §D.6）。演技中の逐次イベントを記録しない
 *       （記録粒度＝結果スコア）。</li>
 *   <li>スコアは試合中イベントの集計ではなく<b>審判団の採点の合算</b>である。MVP は合計点のみを
 *       {@code home/away_score} に整数スケール×1000 で格納する（§4.1）。勝敗は合計点の大小で導出（§B.1.2）。</li>
 *   <li>出場交代の概念がないため STARTER/SUB_IN 等の出場時間系 event_type を含めない
 *       （カタログ検証で弾く・出場時間算出は SCORED で起動しない・01 §D.6）。</li>
 *   <li>得点イベント（GOAL/POINT 等）・カード体系・手数（total_moves）・勝ち方（win_method）を持たない
 *       （いずれも NULL・球技/盤上の専用列を流用しない・§3 / §10）。</li>
 * </ul>
 *
 * <p>MVP は合計点のみゆえ event_type は結果系の少数に限る（{@link MatchEventType#SCORE_SUBMITTED} ＝採点確定・
 * {@link MatchEventType#COMMENT}・{@link MatchEventType#OTHER}）。審判別内訳子表は後段 Phase
 * （{@code match_scored_components}・§4B・MVP では実装しない）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §3 / §10
 *   / 01_domain_and_ddl.md §D.8</p>
 */
public final class ScoredCatalog {

    private ScoredCatalog() {
    }

    /**
     * 採点競技で利用可能な event_type 集合（正準 = sports/07_scored.md §3・結果系少数）。
     * フィギュア・体操で共有する不変集合として公開する。
     */
    public static final Set<MatchEventType> EVENT_TYPES = Collections.unmodifiableSet(
            EnumSet.of(
                    // 採点結果系（採点競技共通・球技/盤上は使わない）
                    MatchEventType.SCORE_SUBMITTED,
                    MatchEventType.COMMENT,
                    // 共通
                    MatchEventType.OTHER));
}
