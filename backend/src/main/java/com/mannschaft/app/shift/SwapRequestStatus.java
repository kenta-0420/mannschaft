package com.mannschaft.app.shift;

/**
 * シフト交代リクエストのステータス区分。
 */
public enum SwapRequestStatus {

    /** 申請中 */
    PENDING,

    /** 相手が承諾 */
    ACCEPTED,

    /** 管理者が承認 */
    APPROVED,

    /** 却下 */
    REJECTED,

    /** キャンセル */
    CANCELLED,

    /**
     * オープンコール（不特定多数募集中）。
     *
     * <p><b>廃止済み・新規に書き込む経路は無い（CMP-260903-0655）。</b> 交代リクエストの作成経路は
     * {@code isOpenCall=true} でも status を {@link #PENDING} のままにしており、この値へ遷移させる
     * 実装がどこにも無かったため、手挙げ（claim）と候補者選定の API ごと削除した。
     *
     * <p>定数だけを残しているのは、永続化された enum だからである。万一この値を持つ行が DB に
     * 残っていた場合、定数を消すと読み出し時に例外で落ちる。<b>掃除のつもりで削除しないこと。</b>
     */
    OPEN_CALL,

    /**
     * 手挙げ済み（先着1名が確定）。
     *
     * <p><b>廃止済み・新規に書き込む経路は無い（CMP-260903-0655）。</b> {@link #OPEN_CALL} を
     * 前提とする状態であり、そちらが発生しない以上この値も発生しない。残置理由は
     * {@link #OPEN_CALL} と同じ（永続 enum の読み出し互換）。
     */
    CLAIMED
}
