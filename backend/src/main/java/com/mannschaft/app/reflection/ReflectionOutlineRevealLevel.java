package com.mannschaft.app.reflection;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OUTLINE section の段階式マスク（足場ラダー）の開示レベル（F06.5・§13-B/§13-C 増分）。
 *
 * <p>足場の段数は <b>到来済み想起予定日の個数 {@code k}</b>（{@code ≤ today} に絞った
 * {@link com.mannschaft.app.reflection.service.ReflectionMaskEvaluator#arrivedDueDates} の個数）で
 * 決定論的に決まる。{@code recall_attempts} 件数には依存しない。</p>
 *
 * <ul>
 *   <li>{@code k ≤ 2} → {@link #FULL}: main_theme・各 OUTLINE section の heading を<b>全文</b>表示（小見出し/詳細/補足は非搭載）</li>
 *   <li>{@code k == 3} → {@link #PARTIAL}: main_theme・heading を<b>先頭 3 コードポイント</b>まで（サーバ側で切る）</li>
 *   <li>{@code k ≥ 4} → {@link #HIDDEN}: 足場ゼロ（main_theme=null・sections 空＝従来の完全マスクと等価）</li>
 * </ul>
 *
 * <p><b>fail-closed</b>: {@code today == null}・parse 例外・型不整合・{@code k ≤ 0}（マスク対象外）は
 * すべて {@link #HIDDEN}（足場を一切出さない）にフォールバックする。</p>
 */
@Schema(name = "ReflectionOutlineRevealLevel",
        description = "OUTLINE 段階式マスク（足場ラダー）の開示レベル。到来済み想起予定日数 k で決定論的に決まる（k≤2=FULL/k==3=PARTIAL/k≥4=HIDDEN）。")
public enum ReflectionOutlineRevealLevel {
    /** main_theme・heading を全文表示（k ≤ 2）。 */
    FULL,
    /** main_theme・heading を先頭 3 コードポイントまで（k == 3・サーバ側で切る）。 */
    PARTIAL,
    /** 足場ゼロ（k ≥ 4・fail-closed・従来の完全マスクと等価）。 */
    HIDDEN
}
