package com.mannschaft.app.common.storage.acl;

/** ストレージオブジェクトに対する登録状態。 */
public enum StorageAclStatus {
    PENDING,
    CLAIMED,
    REVOKED,
    EXPIRED
}
