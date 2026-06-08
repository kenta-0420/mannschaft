package com.mannschaft.app.match.catalog;

/**
 * 警告（Caution）の理由コード（<b>サッカー固有</b>・JFA 競技規則 標準・C1〜C8）。
 *
 * <p>{@code match_events.card_reason_code}（VARCHAR(8)）に保持し、{@code event_type=YELLOW_CARD}
 * および {@code SECOND_YELLOW}（2 枚目の警告）に紐づく。補足の自由記述 {@code note} と併存する。</p>
 *
 * <p>⚠️ <b>保守方針</b>: 本カタログは JFA 競技規則（出典 https://www.jfa.jp/laws/）の標準コードに基づく。
 * 競技規則は毎年改定されうるため、実装・改修時は<b>最新の JFA 公式競技規則と必ず照合</b>すること
 * （本列挙は起草時点の標準であり、唯一の正本は JFA 公式競技規則）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/01_soccer.md §5.1</p>
 */
public enum CautionCode {
    /** 反スポーツ的行為 */
    C1,
    /** ラフプレー */
    C2,
    /** 異議（言葉・行動による） */
    C3,
    /** 繰り返しの違反 */
    C4,
    /** 遅延行為 */
    C5,
    /** 距離不足（CK/FK/スローインの規定距離を守らない） */
    C6,
    /** 無許可入（主審の承認を得ずにフィールドへ入る・復帰する） */
    C7,
    /** 無許可去（主審の承認を得ずにフィールドから離れる） */
    C8
}
