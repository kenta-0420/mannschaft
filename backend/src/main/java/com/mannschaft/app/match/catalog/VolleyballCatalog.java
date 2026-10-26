package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * バレーボール競技カタログ（{@code Sport.VOLLEYBALL} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * {@code Sport.VOLLEYBALL} 実体。本機能で初めて<b>セット制（SET_BASED・01 §D.6）</b>を用いる。</p>
 *
 * <p>サッカー/バスケ（連続時間制）との主な差分（sports/04_volleyball.md §1）:</p>
 * <ul>
 *   <li>得点はラリーポイント制。得点種別: {@link MatchEventType#POINT}/{@link MatchEventType#SERVE_ACE}/
 *       {@link MatchEventType#BLOCK_POINT}/{@link MatchEventType#ATTACK_POINT}/{@link MatchEventType#SERVE_ERROR}。
 *       GOAL/FIELD_GOAL は含まない</li>
 *   <li>セット進行: {@link MatchEventType#SET_START}/{@link MatchEventType#SET_END}
 *       （match_sets 子表・§B.5。period には SET_1..5 を補助格納）</li>
 *   <li>規律コード（カード）は MVP 非対象（§5）。YELLOW_CARD/RED_CARD 等は含まない
 *       （card_reason_code はバレーでは NULL のみ許容）</li>
 *   <li>PK 戦（PENALTY_SHOOTOUT）・オウンゴール（OWN_GOAL）はバレーに存在しないため含まない</li>
 * </ul>
 *
 * <p>セット勝敗・デュース・best-of-5 の試合決着ロジックは {@link VolleyballSetRules} に集約する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/04_volleyball.md §2 / §5 / §7</p>
 */
public final class VolleyballCatalog {

    private VolleyballCatalog() {
    }

    /**
     * バレーボールで利用可能な event_type 集合（正準 = sports/04_volleyball.md §2）。
     * 不変集合として公開する。
     */
    public static final Set<MatchEventType> EVENT_TYPES = java.util.Collections.unmodifiableSet(
            EnumSet.of(
                    // 出場・交代（コア共通）
                    MatchEventType.STARTER,
                    MatchEventType.SUB_IN,
                    MatchEventType.SUB_OUT,
                    // セット進行（バレー固有・SET_BASED）
                    MatchEventType.SET_START,
                    MatchEventType.SET_END,
                    // 得点（バレー固有・ラリーポイント）
                    MatchEventType.POINT,
                    MatchEventType.SERVE_ACE,
                    MatchEventType.BLOCK_POINT,
                    MatchEventType.ATTACK_POINT,
                    MatchEventType.SERVE_ERROR,
                    // 共通
                    MatchEventType.INJURY,
                    MatchEventType.OTHER));

    /**
     * バレーボールのポジション語彙（大分類・正準 = sports/04_volleyball.md §7）。
     *
     * <p>{@code player_appearances.position} の必須語彙（先発 6 人＋リベロ）。
     * doughnut「ポジション傾向」はこの 5 分類で束ねる。
     * OH=アウトサイドヒッター / OP=オポジット / MB=ミドルブロッカー / S=セッター / L=リベロ。</p>
     */
    public static final List<String> POSITIONS = List.of("OH", "OP", "MB", "S", "L");
}
