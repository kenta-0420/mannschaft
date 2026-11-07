package com.mannschaft.app.scopefolder.dto;

/**
 * 招待承諾の前に確かめた、配置先フォルダの事前確認結果（CMP-260826-1457）。
 * 例外ではなく結果として返し、どのエラーをどの優先順位で投げるかは呼び出し側（claim）が決める。
 */
public enum InviteFolderCheck {
    /** 本人の有効なフォルダで、招待先のスコープ種別と一致する。 */
    OK,
    /** 存在しない・削除済み・他人のフォルダ。 */
    NOT_FOUND,
    /** フォルダのスコープ種別が招待先と一致しない。 */
    TYPE_MISMATCH
}
