package com.mannschaft.app.match.catalog;

/**
 * 退場（Sending-off）の理由コード（<b>サッカー固有</b>・JFA 競技規則 標準・S1〜S6・CS）。
 *
 * <p>{@code match_events.card_reason_code}（VARCHAR(8)）に保持し、{@code event_type=RED_CARD}（S1〜S6）
 * および {@code SECOND_YELLOW}（CS＝警告 2 回による退場）に紐づく。補足の自由記述 {@code note} と併存する。</p>
 *
 * <p>⚠️ <b>保守方針</b>: 本カタログは JFA 競技規則（出典 https://www.jfa.jp/laws/）の標準コードに基づく。
 * 競技規則は毎年改定されうるため、実装・改修時は<b>最新の JFA 公式競技規則と必ず照合</b>すること
 * （本列挙は起草時点の標準であり、唯一の正本は JFA 公式競技規則）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/01_soccer.md §5.2</p>
 */
public enum SendingOffCode {
    /** 著しく不正なプレー */
    S1,
    /** 乱暴な行為 */
    S2,
    /** つば（人に唾を吐く） */
    S3,
    /** 得点機会阻止（意図的なハンドリングによる） */
    S4,
    /** 得点機会阻止（その他のファウルによる） */
    S5,
    /** 侮辱（攻撃的・侮辱的・下品な発言や身振り） */
    S6,
    /** 警告 2 回（2 枚目の警告による退場＝SECOND_YELLOW に対応） */
    CS
}
