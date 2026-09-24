package com.mannschaft.app.common.storage.acl;

/** 課金スコープとは独立した、ストレージ ACL の所有境界。 */
public enum StorageAclScopeType {
    TEAM,
    ORGANIZATION,
    /** 大会は課金先ではなく、独立した ACL 所有境界として保持する。 */
    TOURNAMENT,
    /** 大会ディビジョンも親大会へ縮退させず、独立した ACL 所有境界として保持する。 */
    TOURNAMENT_DIVISION,
    VILLAGE,
    PERSONAL,
    PUBLIC
}
