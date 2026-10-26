package com.mannschaft.app.pointcard.enums;

/**
 * ポイントカードプロバイダーの業種カテゴリ。設計書 §4.2 準拠。
 */
public enum PointCardCategory {
    /** 小売（百貨店・家電量販店等）。 */
    RETAIL,
    /** コンビニエンスストア。 */
    CONVENIENCE,
    /** 飲食・カフェ。 */
    FOOD,
    /** 交通（鉄道・バス等）。 */
    TRANSPORT,
    /** その他（上記いずれにも該当しない事業者）。 */
    OTHER
}
