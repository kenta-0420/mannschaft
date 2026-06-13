package com.mannschaft.app.match.domain;

/**
 * イベント種別（F08.10 コア・<b>全競技のイベントを保持する競技非依存の器</b>）。
 *
 * <p>{@code match_events.event_type}（VARCHAR・{@code @Enumerated(STRING)}）に格納される。
 * 各競技が<b>どの値を利用できるか</b>は
 * {@link com.mannschaft.app.match.catalog.SportEventCatalog}（案 A・01 §D.3）で競技別に定義する。</p>
 *
 * <p><b>サッカーの具体集合の正準は sports/01_soccer.md §2</b> であり、その集合は
 * {@link com.mannschaft.app.match.catalog.SoccerCatalog#EVENT_TYPES} に置く。
 * 本 enum はコアの器であるため OTHER（その他）を含め全競技横断の値を列挙する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.2
 *   / sports/01_soccer.md §2</p>
 */
public enum MatchEventType {
    // --- 出場・交代 ---
    /** 先発（appearances 生成・in=0） */
    STARTER,
    /** 交代 IN（appearances 生成・in=その分。再出場も同じ） */
    SUB_IN,
    /** 交代 OUT（out=その分） */
    SUB_OUT,

    // --- 得点 ---
    /** 得点（本戦） */
    GOAL,
    /** アシスト（GOAL とは別イベント） */
    ASSIST,
    /** オウンゴール（相手スコアに加算） */
    OWN_GOAL,
    /** PK 成功（本戦得点に加算） */
    PENALTY_GOAL,
    /** PK 失敗（本戦） */
    PENALTY_MISS,
    /** PK 戦の 1 本（home/away_penalty_score へ・本戦集計対象外） */
    PENALTY_SHOOTOUT,

    // --- カード（退場は out 確定に使用） ---
    /** 警告 */
    YELLOW_CARD,
    /** 一発退場（out=その分） */
    RED_CARD,
    /** 2 枚目の警告＝退場（out=その分） */
    SECOND_YELLOW,

    // --- バスケットボール固有: 得点（sports/03_basketball.md §2）---
    /** 2 点シュート成功（バスケ・スコア +2） */
    FIELD_GOAL_2,
    /** 3 点シュート成功（バスケ・スコア +3） */
    FIELD_GOAL_3,
    /** フリースロー成功（バスケ・スコア +1） */
    FREE_THROW,
    /** シュート失敗（バスケ・任意記録・スコア非影響） */
    SHOT_MISS,

    // --- バスケットボール固有: 技術統計（sports/03_basketball.md §2）---
    /** リバウンド */
    REBOUND,
    /** スティール */
    STEAL,
    /** ブロック */
    BLOCK,
    /** ターンオーバー */
    TURNOVER,

    // --- バスケットボール固有: 反則（sports/03_basketball.md §2 / §5）---
    /** パーソナルファウル（5 個で FOUL_OUT・BasketballFoulCode: PF/SF/OF/UF） */
    PERSONAL_FOUL,
    /** テクニカルファウル（BasketballFoulCode: TF） */
    TECHNICAL_FOUL,
    /** 5 ファウル退場 / ディスクォリファイ（out 確定・BasketballFoulCode: DF または NULL） */
    FOUL_OUT,

    // --- その他 ---
    /** GK セーブ */
    SAVE,
    /** 負傷 */
    INJURY,
    /** ピリオド開始（タイマー基準） */
    PERIOD_START,
    /** ピリオド終了 */
    PERIOD_END,
    /** その他（プリセット外・custom_label に自由ラベル名・note に理由メモ） */
    OTHER
}
