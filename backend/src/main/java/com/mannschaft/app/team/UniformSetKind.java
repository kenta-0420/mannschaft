package com.mannschaft.app.team;

/**
 * ユニフォームセットの種別（F08.7.1/05 §8.2）。
 *
 * <p>フィールドプレイヤー用・GK 正・GK 副の 3 種を各シャツ/パンツ/ソックス色で保持する。</p>
 */
public enum UniformSetKind {

    /** フィールドプレイヤー用 */
    FP,

    /** GK 正 */
    GK_PRIMARY,

    /** GK 副 */
    GK_SECONDARY
}
