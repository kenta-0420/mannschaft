package com.mannschaft.app.pointcard.enums;

/**
 * ポイントカードプロバイダーの種別。設計書 §4.1 準拠。
 */
public enum PointCardProviderType {
    /** 外部事業者（東急ポイント・dポイント等）。Phase 1 はこれのみ Seed 投入される。 */
    EXTERNAL,
    /** Phase 2: organization が自店発行するスタンプカード。 */
    SELF_ISSUED_STAMP,
    /** Phase 2: organization が自店発行するチャージ型残高カード。 */
    SELF_ISSUED_BALANCE
}
