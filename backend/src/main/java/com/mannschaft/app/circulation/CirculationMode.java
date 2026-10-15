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
 *   <li>{@link #UNKNOWN} — DB に想定外の文字列（過去データ・手動投入など）が入っていた場合に
 *       {@link CirculationModeConverter} が読み込み時に写像する縮退値。新規作成の入力値は
 *       {@link com.mannschaft.app.circulation.service.CirculationService#createDocument} が拒否する。
 *       既存の異常行を更新した場合は、異常の痕跡を保持するため明示値 {@code UNKNOWN} へ正規化する。</li>
 * </ul>
 */
public enum CirculationMode {
    SIMULTANEOUS,
    SEQUENTIAL,
    HYBRID,
    UNKNOWN
}
