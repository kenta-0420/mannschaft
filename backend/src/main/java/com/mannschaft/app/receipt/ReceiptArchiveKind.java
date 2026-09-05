package com.mannschaft.app.receipt;

/**
 * PDF 原本アーカイブの種別（F08.12 §3.3）。
 *
 * <p>無効化は原本の改ざんではなく状態の追加であるため、{@link #ORIGINAL} を書き換えずに
 * {@link #VOIDED} を別行として追加する。一意制約は {@code (receipt_id, archive_kind)}。</p>
 */
public enum ReceiptArchiveKind {

    /** 発行時に生成した原本。1 領収書に 1 つだけ。以後どんな操作でも書き換えない。 */
    ORIGINAL,

    /** 無効化時に生成した「無効」表示の PDF。1 領収書に高々 1 つ。 */
    VOIDED
}
