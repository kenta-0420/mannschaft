package com.mannschaft.app.circulation;

/**
 * 回覧モード。
 *
 * <ul>
 *   <li>{@link #SIMULTANEOUS} — 同時回覧。全あて先が順序制約なく一斉に押印できる。</li>
 *   <li>{@link #SEQUENTIAL} — 順次回覧。sortOrder 昇順で 1 人ずつ直列に押印する
 *       （自分より前の受信者が全員完了するまで押せない）。</li>
 *   <li>{@link #HYBRID} — 混成回覧。先頭 N 人（sortOrder 0..N-1）は順番に押印し、
 *       その後の残り全員（同一 sortOrder N）は一斉に押印する。N は
 *       {@code sequentialCount} で保持する（1 ≤ N &lt; あて先数）。</li>
 *   <li>{@link #UNKNOWN} — アプリケーションが書き込むことのない縮退値。DB に想定外の
 *       文字列（過去データ・手動投入など）が入っていた場合に
 *       {@link com.mannschaft.app.circulation.CirculationModeConverter} が読み込み時にここへ
 *       写像する。新規作成・更新の入力値としては受け付けない（{@code EnumInputParser} が拒否する）。</li>
 * </ul>
 */
public enum CirculationMode {
    SIMULTANEOUS,
    SEQUENTIAL,
    HYBRID,
    UNKNOWN
}
