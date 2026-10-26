package com.mannschaft.app.jobmatching.enums;

/**
 * 求人投稿のステータス。
 *
 * <p>状態遷移（F13.1 §5.4）:</p>
 * <ul>
 *   <li>{@link #DRAFT} → {@link #OPEN}: publish()</li>
 *   <li>{@link #DRAFT} → {@link #CANCELLED}: discard()</li>
 *   <li>{@link #OPEN} → {@link #CLOSED}: 定員充足または応募締切通過</li>
 *   <li>{@link #OPEN} → {@link #CANCELLED}: cancel()</li>
 * </ul>
 */
public enum JobPostingStatus {

    /** 下書き（公開前・編集中） */
    DRAFT,

    /** 募集中（応募受付中） */
    OPEN,

    /** 募集終了（定員充足または締切通過） */
    CLOSED,

    /** キャンセル（Requester による取り下げ） */
    CANCELLED
}
