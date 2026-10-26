package com.mannschaft.app.cms.entity;

/**
 * {@link BlogMediaR2DeleteRetryEntity} のリトライ状態。
 */
public enum BlogMediaR2DeleteRetryStatus {
    /** 再試行待ち。 */
    PENDING,
    /** R2 削除・使用量減算とも成功済み。 */
    SUCCEEDED,
    /** 試行上限に達し、以後自動では触らない。 */
    ABANDONED
}
