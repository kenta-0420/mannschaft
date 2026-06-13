package com.mannschaft.app.match.domain;

/**
 * 競技種別（F08.10 コア・多競技対応の識別子・01 §D.1）。
 *
 * <p>{@code matches.sport}（VARCHAR・{@code @Enumerated(STRING)}・既定 'SOCCER'）に格納される。
 * 多競技カタログは案 A（enum＋定数）で確定（01 §D.3）。MVP は SOCCER に加え
 * FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO を含む（sports/01_soccer.md §10 新競技の追加手順）。</p>
 *
 * <p>各競技は {@link StateModel} の 3 類型のいずれかに属する（{@link #stateModel()}）。
 * 新競技は (1) 本 enum に追加、(2) 所属類型を宣言、(3) 競技カタログ文書を雛形複製、の 3 ステップで足りる
 * （3 類型に属する限りコアのタイマー/出場時間/集計/権限/可視性は再実装不要・01 §D.6）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1 / §D.3 / §D.6</p>
 */
public enum Sport {

    /** サッカー（連続時間制・前後半・PK 戦）。 */
    SOCCER(StateModel.CONTINUOUS_TIME),

    /** フットサル（連続時間制・前後半・サッカーと同一イベント集合）。 */
    FUTSAL(StateModel.CONTINUOUS_TIME),

    /** バスケットボール（連続時間制・4 クォーター＋オーバータイム）。 */
    BASKETBALL(StateModel.CONTINUOUS_TIME),

    /** バレーボール（セット制・best-of-5・デュース・match_sets 子表）。 */
    VOLLEYBALL(StateModel.SET_BASED),

    /** 将棋（ターン制・総手数・勝敗＋勝ち方・ピリオド無）。 */
    SHOGI(StateModel.TURN_BASED),

    /** 囲碁（ターン制・総手数・勝敗＋勝ち方・ピリオド無）。 */
    GO(StateModel.TURN_BASED);

    private final StateModel stateModel;

    Sport(StateModel stateModel) {
        this.stateModel = stateModel;
    }

    /**
     * 当該競技が属する状態モデル類型（01 §D.6・正準マッピング）。
     *
     * <p>{@code matches.state_model} の DEFAULT 導出に用いる。Service/FE はこの類型で分岐し、
     * 競技ごとの個別実装を避ける（タイマー/出場時間/COMPLETED バリデーション）。</p>
     *
     * @return 状態モデル類型（CONTINUOUS_TIME / SET_BASED / TURN_BASED）
     */
    public StateModel stateModel() {
        return stateModel;
    }
}
