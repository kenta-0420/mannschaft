package com.mannschaft.app.match.catalog;

/**
 * バスケットボール固有のファウル理由コード（sports/03_basketball.md §5）。
 *
 * <p>コアの汎用カラム {@code match_events.card_reason_code}（VARCHAR(8)）に保持される
 * バスケ専用の理由コード記号。FIBA 競技規則に準拠する（出典:
 * <a href="https://www.fiba.basketball/documents">FIBA公式規則</a>）。</p>
 *
 * <p>各コードと {@link com.mannschaft.app.match.domain.MatchEventType} の対応は
 * {@link BasketballFoulReasonCatalog#ALLOWED_CODES} で定義する:</p>
 * <ul>
 *   <li>{@code PERSONAL_FOUL} → PF / SF / OF / UF</li>
 *   <li>{@code TECHNICAL_FOUL} → TF</li>
 *   <li>{@code FOUL_OUT} → DF（ディスクォリファイ時）/ NULL（5 ファウル累積退場時は不要）</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/03_basketball.md §5</p>
 */
public enum BasketballFoulCode {

    /** パーソナルファウル（一般）— Personal Foul */
    PF,

    /** シューティングファウル（シュート動作中）— Shooting Foul */
    SF,

    /** オフェンスファウル — Offensive Foul */
    OF,

    /** テクニカルファウル — Technical Foul */
    TF,

    /** アンスポーツマンライクファウル — Unsportsmanlike Foul */
    UF,

    /** ディスクォリファイングファウル（即退場）— Disqualifying Foul */
    DF
}
