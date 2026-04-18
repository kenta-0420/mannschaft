package com.mannschaft.app.actionmemo.enums;

/**
 * F02.5 Phase 3 行動メモカテゴリ。
 *
 * <p>メモ1件に対して必ず1つのカテゴリが付与される（NOT NULL）。
 * 省略時は {@code PRIVATE} がデフォルト適用される（設計書 §4.1）。</p>
 */
public enum ActionMemoCategory {

    /** 仕事。チームタイムライン投稿の対象となる唯一のカテゴリ */
    WORK,

    /** 私事（デフォルト）。チームには投稿されない */
    PRIVATE,

    /** その他。チームには投稿されない */
    OTHER
}
