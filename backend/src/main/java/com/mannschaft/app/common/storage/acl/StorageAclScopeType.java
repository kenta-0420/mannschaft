package com.mannschaft.app.common.storage.acl;

/** 課金スコープとは独立した、ストレージ ACL の所有境界。 */
public enum StorageAclScopeType {
    TEAM,
    ORGANIZATION,
    VILLAGE,
    PERSONAL,
    PUBLIC
}
