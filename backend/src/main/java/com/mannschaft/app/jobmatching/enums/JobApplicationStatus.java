package com.mannschaft.app.jobmatching.enums;

/**
 * 求人応募のステータス。
 *
 * <p>状態遷移（F13.1 §5.4）:</p>
 * <ul>
 *   <li>{@link #APPLIED} → {@link #ACCEPTED}: Requester が採用確定</li>
 *   <li>{@link #APPLIED} → {@link #REJECTED}: Requester が不採用</li>
 *   <li>{@link #APPLIED} → {@link #WITHDRAWN}: 応募者が応募取り下げ</li>
 * </ul>
 */
public enum JobApplicationStatus {

    /** 応募中（Requester の判断待ち） */
    APPLIED,

    /** 採用（契約成立 = job_contracts レコード生成） */
    ACCEPTED,

    /** 不採用 */
    REJECTED,

    /** 応募取り下げ */
    WITHDRAWN
}
