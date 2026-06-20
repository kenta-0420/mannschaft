package com.mannschaft.app.reflection;

/**
 * 想起テストの自己評価（F06.5・§2.4 / §3.1）。
 *
 * <p>{@code recall_attempts.self_rating} の CHECK 制約値と完全一致させること。</p>
 */
public enum RecallSelfRating {
    /** 思い出せた → 開示（マスク解除）。 */
    REMEMBERED,
    /** 部分的に思い出せた → 開示（マスク解除）。 */
    PARTIAL,
    /** 思い出せなかった → 再提示（マスク継続）＋翌日 SPACED 再生成（AC-22）。 */
    FORGOT
}
