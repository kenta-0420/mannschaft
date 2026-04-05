package com.mannschaft.app.proxyvote;

/**
 * 議案別投票状態。
 */
public enum VotingStatus {
    /** 未開始 */
    PENDING,
    /** 投票受付中 */
    VOTING,
    /** 投票完了・集計確定 */
    VOTED
}
