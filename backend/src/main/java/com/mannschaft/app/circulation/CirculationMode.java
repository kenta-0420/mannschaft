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
 * </ul>
 */
public enum CirculationMode {
    SIMULTANEOUS,
    SEQUENTIAL,
    HYBRID
}
