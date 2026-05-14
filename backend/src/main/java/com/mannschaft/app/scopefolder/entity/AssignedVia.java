package com.mannschaft.app.scopefolder.entity;

/**
 * フォルダアイテム割当経路の監査用区分。
 *
 * <p>{@code my_scope_folder_items.assigned_via} カラムの値に対応する。
 * 設計書: docs/features/F15.3_scope_folder_integration.md §4.3</p>
 */
public enum AssignedVia {

    /** 招待画面でユーザーが明示的にフォルダを選択して参加した場合。 */
    INVITE,

    /** ハブ画面の DnD / bulk-assign / addItem 等、手動で割り当てた場合。 */
    MANUAL,

    /** バッチ移行（過去データ取り込み等）で割り当てられた場合。 */
    MIGRATION,

    /** 招待時にフォルダ未選択で「未分類」へ自動配置された場合。 */
    DEFAULT
}
